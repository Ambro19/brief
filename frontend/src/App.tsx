import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, NetworkError, api } from "./api";
import { AuthPanel } from "./components/AuthPanel";
import { Composer } from "./components/Composer";
import { InstallHint } from "./components/InstallHint";
import { MessageItem } from "./components/MessageItem";
import { mentionsName } from "./lib/richText";
import { loadSeenMentionId, loadSession, saveSeenMentionId, saveSession } from "./lib/session";
import type { Message, MessagePage, PublicAccount, Session } from "./types";

/** Which list of posts is on screen. Kept in the URL hash so a view can be linked and reloaded. */
type View = { kind: "all" } | { kind: "user"; accountId: number } | { kind: "mentions" };

function viewFromHash(hash: string): View {
  if (hash === "#/mentions") return { kind: "mentions" };
  const match = /^#\/u\/(\d+)$/.exec(hash);
  return match ? { kind: "user", accountId: Number(match[1]) } : { kind: "all" };
}

function newestFirst(a: Message, b: Message): number {
  return b.time_posted_epoch - a.time_posted_epoch || b.message_id - a.message_id;
}

type LoadState = "loading" | "ready" | "offline";

/** The posts on screen, and whether the server has older ones that are not loaded yet. */
type Feed = { items: Message[]; hasMore: boolean };

const EMPTY_FEED: Feed = { items: [], hasMore: false };

/** How many posts are asked for at a time. */
const PAGE_SIZE = 10;

/** How many mentions are asked for at a time. */
const MENTION_PAGE_SIZE = 20;

/**
 * Folds a freshly read newest page into what is already on screen.
 * Posts inside the page's id range are replaced by the page (so edits and deletions show up);
 * older posts that were loaded earlier with "Show older posts" are kept.
 */
function mergeNewestPage(feed: Feed, page: MessagePage): Feed {
  const onScreen = new Set(feed.items.map((m) => m.message_id));
  const overlaps = page.items.some((m) => onScreen.has(m.message_id));
  // Start over from the page alone when it is the whole list, or when it shares no post with the
  // screen: then more than a page of new posts arrived, and keeping the old ones would hide a gap.
  if (!page.hasMore || !overlaps) return { items: [...page.items].sort(newestFirst), hasMore: page.hasMore };

  const oldestInPage = Math.min(...page.items.map((m) => m.message_id));
  const olderOnScreen = feed.items.filter((m) => m.message_id < oldestInPage);
  return {
    items: [...page.items, ...olderOnScreen].sort(newestFirst),
    hasMore: olderOnScreen.length > 0 ? feed.hasMore : true,
  };
}

/** Whether this browser may show desktop notifications. "unsupported" covers old browsers and plain-http pages. */
type AlertPermission = NotificationPermission | "unsupported";

function currentAlertPermission(): AlertPermission {
  return "Notification" in window ? Notification.permission : "unsupported";
}

/** How often the feed and the mentions are re-read from the server. */
const POLL_INTERVAL_MS = 15_000;

/** True while developing: the page comes from this computer or the home network, not from the deployed site. */
const onOwnMachine = /^(localhost$|127\.|10\.|192\.168\.)/.test(window.location.hostname);

export default function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [view, setView] = useState<View>(() => viewFromHash(window.location.hash));
  const [feed, setFeed] = useState<Feed>(EMPTY_FEED);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [accounts, setAccounts] = useState<PublicAccount[]>([]);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [toast, setToast] = useState<string | null>(null);
  const [newId, setNewId] = useState<number | null>(null);
  /** Posts that mention the signed-in member: the newest page, plus older ones loaded on request. */
  const [mentionFeed, setMentionFeed] = useState<Feed>(EMPTY_FEED);
  const [loadingOlderMentions, setLoadingOlderMentions] = useState(false);
  const [seenMentionId, setSeenMentionId] = useState(0);
  const [alertPermission, setAlertPermission] = useState<AlertPermission>(currentAlertPermission);
  const toastTimer = useRef<number>();
  /** Highest mention id received so far; null until the first load, so old mentions never raise an alert. */
  const newestMentionId = useRef<number | null>(null);

  const messages = feed.items;
  const mentions = mentionFeed.items;
  const names = useMemo(() => new Map(accounts.map((a) => [a.account_id, a.username])), [accounts]);
  // Lower case: since 2.3 the server treats "Maya" and "maya" as the same name.
  const takenUsernames = useMemo(() => new Set(accounts.map((a) => a.username.toLowerCase())), [accounts]);
  const viewedAccountId = view.kind === "user" ? view.accountId : null;
  /** The list on screen right now, readable from inside a request that started earlier. */
  const currentList = useRef(viewedAccountId);
  currentList.current = viewedAccountId;
  /** Whose posts `feed` holds: null = everyone's, a number = that member's, undefined = nothing loaded yet. */
  const loadedList = useRef<number | null | undefined>(undefined);
  const myId = session?.account_id ?? null;
  /** Who is signed in right now, readable from inside a request that started earlier. */
  const currentMember = useRef(myId);
  currentMember.current = myId;
  const myName = session?.username ?? null;
  const unreadMentions = mentions.filter((m) => m.message_id > seenMentionId).length;
  // Every loaded mention is unread and the server has more: the true number is at least this.
  const unreadLabel = unreadMentions > 0 && unreadMentions === mentions.length && mentionFeed.hasMore ? `${unreadMentions}+` : String(unreadMentions);
  const mentionIds = useMemo(() => new Set(mentions.map((m) => m.message_id)), [mentions]);
  /** The oldest mention that has been loaded. For posts older than this, the server's list is not known here. */
  const oldestMentionId = useMemo(() => (mentions.length ? Math.min(...mentions.map((m) => m.message_id)) : 0), [mentions]);

  /** True if this post names the signed-in member. */
  function mentionsMe(message: Message): boolean {
    if (mentionIds.has(message.message_id)) return true;
    // Older than everything loaded, with more mentions still on the server: apply the server's own rule here.
    const beyondLoaded = mentionFeed.hasMore && message.message_id < oldestMentionId;
    return beyondLoaded && myName !== null && message.posted_by !== myId && mentionsName(message.message_text, myName);
  }

  const notify = useCallback((text: string) => {
    setToast(text);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 3200);
  }, []);

  const refresh = useCallback(async () => {
    try {
      const [people, page] = await Promise.all([api.accounts(), api.messagesPage(viewedAccountId, PAGE_SIZE)]);
      if (currentList.current !== viewedAccountId) return; // the reader moved to another list while this was loading
      const sameList = loadedList.current === viewedAccountId;
      loadedList.current = viewedAccountId;
      setAccounts(people);
      // A different list replaces what is on screen; the same list is merged, so older posts already loaded stay.
      setFeed((current) => mergeNewestPage(sameList ? current : EMPTY_FEED, page));
      setLoadState("ready");
    } catch {
      setLoadState("offline");
    }
  }, [viewedAccountId]);

  /**
   * Reads the newest page of mentions of the signed-in member, and announces the ones that arrived
   * since the last read. Until 2.2 this fetched every mention ever received, every 15 seconds; now
   * it is one page, however long the history is.
   */
  const loadMentions = useCallback(async () => {
    if (myId === null) return;
    let page: MessagePage;
    try {
      page = await api.mentionsPage(myId, MENTION_PAGE_SIZE);
    } catch {
      return; // the status line already reports an unreachable server
    }
    if (currentMember.current !== myId) return; // someone else signed in while this was loading
    const list = page.items;
    const newest = list.reduce((max, m) => Math.max(max, m.message_id), 0);
    const previous = newestMentionId.current;
    newestMentionId.current = Math.max(newest, previous ?? 0);
    // Merged like the feed: the newest page replaces its range, older mentions already loaded stay.
    setMentionFeed((current) => mergeNewestPage(current, page));

    if (previous === null) return; // first load after signing in: show the count, do not alert
    const arrived = list.filter((m) => m.message_id > previous);
    if (arrived.length === 0) return;

    const latest = [...arrived].sort(newestFirst)[0];
    const who = names.get(latest.posted_by) ?? "Someone";
    const summary = arrived.length === 1 ? `${who} mentioned you` : `${arrived.length} new mentions`;
    notify(summary);
    // A desktop notification reaches people who are looking at another window.
    if (document.hidden && currentAlertPermission() === "granted") {
      new Notification("Brief!", { body: `${summary}: ${latest.message_text}`, tag: "brief-mention" });
    }
  }, [myId, names, notify]);

  // Load when the view changes, and again whenever the tab regains focus.
  useEffect(() => {
    setLoadState("loading");
    void refresh();
    const onFocus = () => void refresh();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [refresh]);

  // A different member (or nobody) is signed in: start their mention state from scratch.
  useEffect(() => {
    newestMentionId.current = null;
    setMentionFeed(EMPTY_FEED);
    setSeenMentionId(myId === null ? 0 : loadSeenMentionId(myId));
  }, [myId]);

  // Keep the screen current without a page reload. The feed is only re-read while the tab is visible;
  // mentions are read even in the background, so a desktop alert can fire.
  const poll = useRef(() => {});
  poll.current = () => {
    if (!document.hidden) void refresh();
    void loadMentions();
  };
  useEffect(() => {
    void loadMentions();
    const timer = window.setInterval(() => poll.current(), POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
    // Restart only when the signed-in member changes; poll.current always holds the latest functions.
  }, [myId]);

  // Opening the Mentions view marks everything in it as read.
  useEffect(() => {
    if (view.kind !== "mentions" || myId === null || mentions.length === 0) return;
    const newest = mentions.reduce((max, m) => Math.max(max, m.message_id), 0);
    if (newest > seenMentionId) {
      setSeenMentionId(newest);
      saveSeenMentionId(myId, newest);
    }
  }, [view.kind, myId, mentions, seenMentionId]);

  // Show the unread count in the browser tab, where it is visible from other tabs,
  // and on the icon of the installed app, where the system supports that.
  useEffect(() => {
    document.title = unreadMentions > 0 ? `(${unreadLabel}) Brief!` : "Brief!";
    const badge = navigator as Navigator & { setAppBadge?: (count?: number) => Promise<void>; clearAppBadge?: () => Promise<void> };
    // Both calls can be missing (most browsers) or refused (no permission); neither matters to the page.
    if (unreadMentions > 0) void badge.setAppBadge?.(unreadMentions)?.catch(() => {});
    else void badge.clearAppBadge?.()?.catch(() => {});
  }, [unreadMentions, unreadLabel]);

  useEffect(() => {
    const onHashChange = () => setView(viewFromHash(window.location.hash));
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  function go(next: View) {
    window.location.hash = next.kind === "user" ? `#/u/${next.accountId}` : next.kind === "mentions" ? "#/mentions" : "#/";
  }

  async function turnOnAlerts() {
    if (!("Notification" in window)) return;
    setAlertPermission(await Notification.requestPermission());
  }

  /** Loads the next page: posts older than the oldest one on screen. */
  async function loadOlder() {
    if (loadingOlder || messages.length === 0) return;
    const oldest = Math.min(...messages.map((m) => m.message_id));
    setLoadingOlder(true);
    try {
      const page = await api.messagesPage(viewedAccountId, PAGE_SIZE, oldest);
      if (currentList.current !== viewedAccountId) return;
      setFeed((current) => {
        const onScreen = new Set(current.items.map((m) => m.message_id));
        const added = page.items.filter((m) => !onScreen.has(m.message_id));
        return { items: [...current.items, ...added].sort(newestFirst), hasMore: page.hasMore };
      });
    } catch {
      notify("Older posts couldn't be loaded. Try again.");
    } finally {
      setLoadingOlder(false);
    }
  }

  /** Loads the next page of mentions: older than the oldest one on screen. */
  async function loadOlderMentions() {
    if (loadingOlderMentions || myId === null || mentions.length === 0) return;
    setLoadingOlderMentions(true);
    try {
      const page = await api.mentionsPage(myId, MENTION_PAGE_SIZE, oldestMentionId);
      if (currentMember.current !== myId) return;
      setMentionFeed((current) => {
        const onScreen = new Set(current.items.map((m) => m.message_id));
        const added = page.items.filter((m) => !onScreen.has(m.message_id));
        return { items: [...current.items, ...added].sort(newestFirst), hasMore: page.hasMore };
      });
    } catch {
      notify("Older mentions couldn't be loaded. Try again.");
    } finally {
      setLoadingOlderMentions(false);
    }
  }

  function signedIn(next: Session, isNewAccount: boolean, stay: boolean) {
    setSession(next);
    saveSession(next, stay);
    notify(isNewAccount ? `Account created. Welcome, ${next.username}.` : `Signed in as ${next.username}`);
    void refresh();
  }

  function endSession(message: string) {
    setSession(null);
    saveSession(null);
    notify(message);
  }

  async function signOut() {
    if (session) {
      try {
        await api.logout(session.token);
      } catch {
        /* signing out locally is enough */
      }
    }
    endSession("Signed out");
  }

  /** Shared handling for a failed write: tells the user what happened and what to do. */
  function explainWriteFailure(problem: unknown) {
    if (problem instanceof ApiError && problem.status === 401) {
      endSession("Your session ended. Sign in again.");
    } else if (problem instanceof ApiError && problem.status === 403) {
      notify("You can only change your own posts.");
    } else if (problem instanceof NetworkError) {
      notify("The server isn't responding. Nothing was changed.");
    } else {
      notify("That didn't go through. Check the length and try again.");
    }
  }

  async function post(text: string): Promise<boolean> {
    if (!session) return false;
    try {
      const created = await api.createMessage(session, text);
      setNewId(created.message_id);
      if (viewedAccountId === null || viewedAccountId === session.account_id) {
        setFeed((current) => ({ ...current, items: [created, ...current.items].sort(newestFirst) }));
      }
      notify("Posted");
      return true;
    } catch (problem) {
      explainWriteFailure(problem);
      return false;
    }
  }

  async function save(messageId: number, text: string): Promise<boolean> {
    if (!session) return false;
    try {
      const updated = await api.updateMessage(session, messageId, text);
      setFeed((current) => ({ ...current, items: current.items.map((m) => (m.message_id === messageId ? updated : m)) }));
      notify("Saved");
      return true;
    } catch (problem) {
      explainWriteFailure(problem);
      return false;
    }
  }

  async function remove(messageId: number): Promise<void> {
    if (!session) return;
    try {
      await api.deleteMessage(session, messageId);
      setFeed((current) => ({ ...current, items: current.items.filter((m) => m.message_id !== messageId) }));
      notify("Deleted");
      // That was the last post on screen but the server has older ones: fetch them rather than show "nothing here".
      if (messages.length === 1 && feed.hasMore) void refresh();
    } catch (problem) {
      explainWriteFailure(problem);
    }
  }

  const showingMentions = view.kind === "mentions" && session !== null;
  const shown = showingMentions ? mentions : messages;
  const viewedName = viewedAccountId === null ? null : (names.get(viewedAccountId) ?? `user ${viewedAccountId}`);
  const viewingSelf = session !== null && viewedAccountId === session.account_id;
  const heading = showingMentions
    ? "Mentions of you"
    : viewedAccountId === null
      ? "Everyone"
      : viewingSelf
        ? "Your posts"
        : `Posts by ${viewedName}`;

  return (
    <div className="shell">
      <aside className="rail">
        <a className="brand" href="#/" aria-label="Brief!, home">
          <span className="brand-name">Brief!</span>
          <span className="brand-line">Say it in 255.</span>
        </a>
        <p className="brand-about">A small message board. Every post fits in 255 characters, so every post gets read.</p>

        {session ? (
          <section className="me" aria-label="Your account">
            <p className="me-label">Signed in as</p>
            <p className="me-name">{session.username}</p>
            <div className="me-actions">
              <button type="button" className="btn btn-quiet" onClick={() => go({ kind: "mentions" })}>
                Mentions
                {unreadMentions > 0 && (
                  <span className="badge" aria-label={`${unreadLabel} new`}>
                    {unreadLabel}
                  </span>
                )}
              </button>
              <button type="button" className="btn btn-quiet" onClick={() => go({ kind: "user", accountId: session.account_id })}>
                Your posts
              </button>
              <button type="button" className="btn btn-quiet" onClick={() => void signOut()}>
                Sign out
              </button>
            </div>
          </section>
        ) : (
          <AuthPanel onSignedIn={signedIn} takenUsernames={loadState === "ready" ? takenUsernames : null} />
        )}

        <p className="status" data-state={loadState}>
          <span className="status-dot" aria-hidden="true" />
          {loadState === "offline" ? "Server not reachable" : loadState === "loading" ? "Connecting…" : `Server online, ${accounts.length} ${accounts.length === 1 ? "member" : "members"}`}
        </p>
        <p className="rail-links">
          <a href="/docs/index.html">API docs</a>
        </p>
        <InstallHint />
      </aside>

      <main className="main">
        <header className="main-head">
          <h1>{heading}</h1>
          <div className="main-head-actions">
            {view.kind !== "all" && (
              <button type="button" className="btn btn-quiet" onClick={() => go({ kind: "all" })}>
                Back to everyone
              </button>
            )}
            <button
              type="button"
              className="btn btn-quiet"
              onClick={() => {
                void refresh();
                void loadMentions();
              }}
            >
              Refresh
            </button>
          </div>
        </header>

        {session ? (
          <Composer username={session.username} onPost={post} />
        ) : (
          <p className="signin-nudge">Reading is open to everyone. Sign in to post.</p>
        )}

        {showingMentions && alertPermission !== "unsupported" && (
          <p className="alerts">
            {alertPermission === "granted" ? (
              "Desktop alerts are on. You'll be told about new mentions while Brief! is open in a tab."
            ) : alertPermission === "denied" ? (
              "Desktop alerts are blocked for this site in your browser settings."
            ) : (
              <>
                Want to know when you're mentioned while you work in another window?{" "}
                <button type="button" className="link" onClick={() => void turnOnAlerts()}>
                  Turn on desktop alerts
                </button>
              </>
            )}
          </p>
        )}

        {loadState === "offline" ? (
          <div className="notice" role="alert">
            <p className="notice-title">{window.navigator.onLine ? "The server isn't responding." : "You're offline."}</p>
            <p>
              {!window.navigator.onLine
                ? "Posts will load again when your connection is back."
                : onOwnMachine
                  ? "Start the API (it listens on port 8080), then refresh."
                  : "It may be starting up. Try again in a moment."}
            </p>
            <button type="button" className="btn btn-primary" onClick={() => void refresh()}>
              Try again
            </button>
          </div>
        ) : loadState === "loading" && shown.length === 0 ? (
          <p className="quiet">Loading posts…</p>
        ) : shown.length === 0 ? (
          <div className="notice">
            <p className="notice-title">
              {showingMentions
                ? "No one has mentioned you yet."
                : viewedAccountId === null
                  ? "Nothing here yet."
                  : viewingSelf
                    ? "You haven't posted yet."
                    : `${viewedName} hasn't posted yet.`}
            </p>
            <p>
              {showingMentions
                ? `When someone writes "${session?.username}" in a post, it appears here.`
                : session
                  ? "Write the first post in the box above."
                  : "Sign in and write the first post."}
            </p>
          </div>
        ) : (
          <>
            <ol className="feed" aria-label={heading}>
              {shown.map((message) => (
                <MessageItem
                  key={message.message_id}
                  message={message}
                  authorName={names.get(message.posted_by) ?? `user ${message.posted_by}`}
                  accounts={accounts}
                  isOwn={session?.account_id === message.posted_by}
                  isNew={message.message_id === newId}
                  mentionsYou={mentionsMe(message)}
                  onOpenAuthor={(accountId) => go({ kind: "user", accountId })}
                  onSave={save}
                  onDelete={remove}
                />
              ))}
            </ol>
            {!showingMentions && feed.hasMore && (
              <p className="feed-more">
                <button type="button" className="btn btn-quiet" disabled={loadingOlder} onClick={() => void loadOlder()}>
                  {loadingOlder ? "Loading…" : "Show older posts"}
                </button>
              </p>
            )}
            {showingMentions && mentionFeed.hasMore && (
              <p className="feed-more">
                <button type="button" className="btn btn-quiet" disabled={loadingOlderMentions} onClick={() => void loadOlderMentions()}>
                  {loadingOlderMentions ? "Loading…" : "Show older mentions"}
                </button>
              </p>
            )}
          </>
        )}
      </main>

      <div className="toast" role="status" aria-live="polite" data-show={toast !== null}>
        {toast}
      </div>
    </div>
  );
}
