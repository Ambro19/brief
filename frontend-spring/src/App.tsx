import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ApiError, NetworkError, api } from "./api";
import { AuthPanel } from "./components/AuthPanel";
import { Composer } from "./components/Composer";
import { AccountPanel } from "./components/AccountPanel";
import { EmailStatus } from "./components/EmailStatus";
import { InvitePanel } from "./components/InvitePanel";
import type { OpenInvitation } from "./components/AuthPanel";
import { InstallHint } from "./components/InstallHint";
import { MessageItem } from "./components/MessageItem";
import { takeMailedLink } from "./lib/links";
import { mentionsName } from "./lib/richText";
import { loadSeenMentionId, loadSession, saveSeenMentionId, saveSession } from "./lib/session";
import type { Message, MessagePage, Profile, PublicAccount, ServerInfo, Session } from "./types";

/** Which list of posts is on screen. Kept in the URL hash so a view can be linked and reloaded. */
type View =
  | { kind: "all" }
  | { kind: "user"; accountId: number }
  | { kind: "mentions" }
  | { kind: "invite" } // the board owner's "Invite people" (2.5)
  | { kind: "account" }; // "Your account", with deletion (2.5)

function viewFromHash(hash: string): View {
  if (hash === "#/mentions") return { kind: "mentions" };
  if (hash === "#/invite") return { kind: "invite" };
  if (hash === "#/account") return { kind: "account" };
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

/**
 * A link from an email this page was opened with (see lib/links.ts), read once when the app
 * starts. Reading it here, outside the component, means it is read exactly once: React may
 * run a component's own start-up code twice while developing.
 */
const mailedLink = takeMailedLink();

/** The one request that uses a confirmation link. Kept here so that it, too, is made exactly once. */
let confirming: Promise<"confirmed" | "refused" | "unreachable"> | null = null;

function confirmAddress(secret: string): Promise<"confirmed" | "refused" | "unreachable"> {
  confirming ??= api.verifyEmail(secret).then(
    () => "confirmed" as const,
    (problem) => (problem instanceof NetworkError ? ("unreachable" as const) : ("refused" as const)),
  );
  return confirming;
}

/** A message at the top of the page that stays until it is dismissed (a toast is gone in three seconds). */
type PageNotice = { text: string; tone: "good" | "bad" };

export default function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  /** What the server said about itself (GET /health); null until it has answered. */
  const [server, setServer] = useState<ServerInfo | null>(null);
  const serverKnown = useRef<ServerInfo | null>(null);
  /** The signed-in member's own account, from a server that has email (2.4); null otherwise. */
  const [profile, setProfile] = useState<Profile | null>(null);
  /** The secret of a password link the page was opened with, until it is used or put aside. */
  const [resetSecret, setResetSecret] = useState<string | null>(mailedLink?.kind === "reset" ? mailedLink.secret : null);
  const [pageNotice, setPageNotice] = useState<PageNotice | null>(null);
  /** 2.7: counts up on Refresh and on every poll, so "Invite people" re-reads its list (a bounce arrives seconds after sending). */
  const [inviteTick, setInviteTick] = useState(0);
  /** An invitation link this page was opened with, once the server has said it still works (2.5). */
  const [invitation, setInvitation] = useState<OpenInvitation | null>(null);
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
  /** The login token, sent with every read: a closed board (2.4) shows its posts to members only. */
  const myToken = session?.token;
  const unreadMentions = mentions.filter((m) => m.message_id > seenMentionId).length;
  // Every loaded mention is unread and the server has more: the true number is at least this.
  const unreadLabel =
    unreadMentions > 0 && unreadMentions === mentions.length && mentionFeed.hasMore ? `${unreadMentions}+` : String(unreadMentions);
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

  /** Asks the server what it is like, and remembers the answer. Throws if it cannot be reached. */
  const loadServer = useCallback(async (): Promise<ServerInfo> => {
    const info = await api.serverInfo();
    serverKnown.current = info;
    // Keep the object already in use when nothing changed, so that nothing which depends on it starts over.
    setServer((known) =>
      known !== null && known.passwordPolicy === info.passwordPolicy && known.email === info.email && known.board === info.board
        ? known
        : info,
    );
    return info;
  }, []);

  /** A closed board, seen signed out: there is nothing to list, and nothing is asked for. */
  const showClosedBoard = useCallback(() => {
    loadedList.current = undefined;
    setAccounts([]);
    setFeed(EMPTY_FEED);
    setLoadState("ready");
  }, []);

  const refresh = useCallback(async () => {
    try {
      // The app has to know whether the board is closed before a signed-out visitor asks for
      // posts: a closed board answers 401. Asked once; asked again each time while the answer is
      // "closed, and nobody is signed in", because then it is the only request there is.
      let info = serverKnown.current;
      if (info === null || (info.board === "closed" && myId === null)) info = await loadServer();
      if (currentMember.current !== myId) return; // someone signed in or out while this was loading
      if (info.board === "closed" && myId === null) return showClosedBoard();

      const [people, page] = await Promise.all([api.accounts(myToken), api.messagesPage(viewedAccountId, PAGE_SIZE, undefined, myToken)]);
      if (currentMember.current !== myId) return;
      if (currentList.current !== viewedAccountId) return; // the reader moved to another list while this was loading
      const sameList = loadedList.current === viewedAccountId;
      loadedList.current = viewedAccountId;
      setAccounts(people);
      // A different list replaces what is on screen; the same list is merged, so older posts already loaded stay.
      setFeed((current) => mergeNewestPage(sameList ? current : EMPTY_FEED, page));
      setLoadState("ready");
    } catch (problem) {
      if (problem instanceof ApiError && problem.status === 401) {
        // Only a closed board refuses to show posts. Either this session is no longer accepted
        // (it expired, or the password was changed), or the board was closed while this page was open.
        if (currentMember.current !== myId) return;
        if (myId !== null) return endSession("Your session ended. Sign in again.");
        try {
          if ((await loadServer()).board === "closed") return showClosedBoard();
        } catch {
          /* falls through to "not reachable" */
        }
      }
      setLoadState("offline");
    }
    // endSession is left out on purpose: it is a new function on every render and does the same thing each time.
  }, [viewedAccountId, myId, myToken, loadServer, showClosedBoard]);

  /**
   * Reads the signed-in member's own account (GET /me), which says whether an email address
   * is on file and confirmed. Older servers have no such address and are not asked.
   */
  const loadProfile = useCallback(async (): Promise<Profile | null> => {
    const info = serverKnown.current;
    if (session === null || info === null || info.email === "none") return null;
    try {
      const mine = await api.me(session.token);
      if (currentMember.current === session.account_id) setProfile(mine);
      return mine;
    } catch (problem) {
      // The server no longer accepts this session: it expired, or the password was changed elsewhere.
      if (problem instanceof ApiError && problem.status === 401 && currentMember.current === session.account_id) {
        endSession("Your session ended. Sign in again.");
      }
      return null;
    }
  }, [session]);

  /**
   * Reads the newest page of mentions of the signed-in member, and announces the ones that arrived
   * since the last read. Until 2.2 this fetched every mention ever received, every 15 seconds; now
   * it is one page, however long the history is.
   */
  const loadMentions = useCallback(async () => {
    if (myId === null) return;
    let page: MessagePage;
    try {
      page = await api.mentionsPage(myId, MENTION_PAGE_SIZE, undefined, myToken);
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
      new Notification("Brief!", {
        body: `${summary}: ${latest.message_text}`,
        tag: "brief-mention",
      });
    }
  }, [myId, myToken, names, notify]);

  // Load when the view changes, and again whenever the tab regains focus.
  useEffect(() => {
    setLoadState("loading");
    void refresh();
    const onFocus = () => void refresh();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [refresh]);

  // The member's own account: read after signing in (once the server has said what it is like),
  // and again when the tab regains focus, because the confirmation link usually opens in another tab.
  useEffect(() => {
    setProfile(null);
    if (session === null || server === null) return;
    void loadProfile();
    const onFocus = () => void loadProfile();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [session, server, loadProfile]);

  // The page was opened from a confirmation link: use it, and say what happened.
  useEffect(() => {
    if (mailedLink?.kind !== "verify") return;
    let stillHere = true;
    void confirmAddress(mailedLink.secret).then((outcome) => {
      if (!stillHere) return;
      if (outcome === "confirmed") {
        if (currentMember.current === null) {
          setPageNotice({
            text: "Your email address is confirmed. Sign in to post.",
            tone: "good",
          });
          return;
        }
        // Someone is signed in here. Usually it is the member whose link this was; on a shared
        // computer it can be someone else, and then "you can post now" would not be true for them.
        void (async () => {
          try {
            if (serverKnown.current === null) await loadServer(); // the account can only be read once the server has said what it is like
          } catch {
            /* the status line reports an unreachable server */
          }
          const mine = await latestLoadProfile.current();
          if (!stillHere) return;
          setPageNotice(
            mine !== null && !mine.can_post
              ? {
                  text: "That email address is confirmed. It belongs to a different account from the one signed in here.",
                  tone: "good",
                }
              : {
                  text: "Your email address is confirmed. You can post now.",
                  tone: "good",
                },
          );
        })();
      } else if (outcome === "refused") {
        const refused = {
          text: "That confirmation link has expired or was already used. Sign in to have a new one sent.",
          tone: "bad" as const,
        };
        if (currentMember.current === null) {
          setPageNotice(refused);
          return;
        }
        // 2.7: someone is signed in. An old link opened by a member whose address is already
        // confirmed is not a problem, and should not read like one (live test, October 20).
        void (async () => {
          try {
            if (serverKnown.current === null) await loadServer();
          } catch {
            /* the status line reports an unreachable server */
          }
          const mine = await latestLoadProfile.current();
          if (!stillHere) return;
          setPageNotice(
            mine !== null && mine.email_verified
              ? {
                  text: "That link was already used. The address of the account signed in here is confirmed: there is nothing more to do.",
                  tone: "good",
                }
              : mine !== null && mine.email_bounced
                ? { text: "That confirmation link no longer works. Enter your address again below, and a new one is sent.", tone: "bad" }
                : mine !== null && mine.email !== null
                  ? { text: "That confirmation link has expired or was already used. Use “Send the link again” below.", tone: "bad" }
                  : refused,
          );
        })();
      } else {
        setPageNotice({
          text: "The server isn't responding, so the address could not be confirmed. Open the link in the mail again in a moment.",
          tone: "bad",
        });
      }
    });
    return () => {
      stillHere = false;
    };
    // Runs once: the link is read when the app starts, and loadServer never changes.
  }, [loadServer]);

  // The page was opened from an invitation link (2.5): ask the server who sent it, then open "Create account".
  useEffect(() => {
    if (mailedLink?.kind !== "invite") return;
    const secret = mailedLink.secret;
    let stillHere = true;
    api.lookupInvitation(secret).then(
      (info) => {
        if (!stillHere) return;
        setInvitation({ secret, info });
        if (currentMember.current !== null) {
          setPageNotice({
            text: `${info.inviter} invited someone to Brief! with this link. To use it for a new account, sign out first.`,
            tone: "good",
          });
        }
      },
      (problem) => {
        if (!stillHere) return;
        setPageNotice(
          problem instanceof NetworkError
            ? {
                text: "The server isn't responding, so the invitation could not be read. Open the link again in a moment.",
                tone: "bad",
              }
            : {
                text: "This invitation link no longer works: it was used, withdrawn, or it expired. Ask the person who invited you for a new one.",
                tone: "bad",
              },
        );
      },
    );
    return () => {
      stillHere = false;
    };
  }, []);

  // A different member (or nobody) is signed in: start their mention state from scratch.
  useEffect(() => {
    newestMentionId.current = null;
    setMentionFeed(EMPTY_FEED);
    setSeenMentionId(myId === null ? 0 : loadSeenMentionId(myId));
  }, [myId]);

  // What a mailed link did was said to whoever was looking then. After signing in or out it is taken down.
  const noticeWasFor = useRef(myId);
  useEffect(() => {
    if (noticeWasFor.current !== myId) setPageNotice(null);
    noticeWasFor.current = myId;
  }, [myId]);

  // Keep the screen current without a page reload. The feed is only re-read while the tab is visible;
  // mentions are read even in the background, so a desktop alert can fire.
  const poll = useRef(() => {});
  poll.current = () => {
    if (!document.hidden) void refresh();
    void loadMentions();
    // While posting waits for the address to be confirmed, keep looking: the link is often opened on another device.
    if (profile !== null && !profile.can_post) void loadProfile();
    if (!document.hidden && view.kind === "invite") setInviteTick((tick) => tick + 1);
  };
  /** The newest loadProfile, for code that started before the member signed in. */
  const latestLoadProfile = useRef(loadProfile);
  latestLoadProfile.current = loadProfile;
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
    const badge = navigator as Navigator & {
      setAppBadge?: (count?: number) => Promise<void>;
      clearAppBadge?: () => Promise<void>;
    };
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
    window.location.hash = next.kind === "user" ? `#/u/${next.accountId}` : next.kind === "all" ? "#/" : `#/${next.kind}`;
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
      const page = await api.messagesPage(viewedAccountId, PAGE_SIZE, oldest, myToken);
      if (currentList.current !== viewedAccountId) return;
      setFeed((current) => {
        const onScreen = new Set(current.items.map((m) => m.message_id));
        const added = page.items.filter((m) => !onScreen.has(m.message_id));
        return {
          items: [...current.items, ...added].sort(newestFirst),
          hasMore: page.hasMore,
        };
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
      const page = await api.mentionsPage(myId, MENTION_PAGE_SIZE, oldestMentionId, myToken);
      if (currentMember.current !== myId) return;
      setMentionFeed((current) => {
        const onScreen = new Set(current.items.map((m) => m.message_id));
        const added = page.items.filter((m) => !onScreen.has(m.message_id));
        return {
          items: [...current.items, ...added].sort(newestFirst),
          hasMore: page.hasMore,
        };
      });
    } catch {
      notify("Older mentions couldn't be loaded. Try again.");
    } finally {
      setLoadingOlderMentions(false);
    }
  }

  function signedIn(next: Session, isNewAccount: boolean, stay: boolean) {
    if (isNewAccount) setInvitation(null); // used up
    setSession(next);
    saveSession(next, stay);
    notify(isNewAccount ? `Account created. Welcome, ${next.username}.` : `Signed in as ${next.username}`);
    // Nothing is loaded here: the effects above run again by themselves, because who is signed in changed.
  }

  /** A password link was used (the password is new, every session is over) or put aside. */
  function resetClosed(passwordChanged: boolean) {
    setResetSecret(null);
    if (passwordChanged && session) {
      setSession(null);
      saveSession(null);
    }
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
      // 403 has two meanings: the post is someone else's, or this account's address is not confirmed yet. Look which.
      void loadProfile().then((mine) =>
        notify(
          mine !== null && !mine.can_post
            ? "Confirm your email address first. The link is in your inbox."
            : "You can only change your own posts.",
        ),
      );
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
        setFeed((current) => ({
          ...current,
          items: [created, ...current.items].sort(newestFirst),
        }));
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
      setFeed((current) => ({
        ...current,
        items: current.items.map((m) => (m.message_id === messageId ? updated : m)),
      }));
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
      setFeed((current) => ({
        ...current,
        items: current.items.filter((m) => m.message_id !== messageId),
      }));
      notify("Deleted");
      // That was the last post on screen but the server has older ones: fetch them rather than show "nothing here".
      if (messages.length === 1 && feed.hasMore) void refresh();
    } catch (problem) {
      explainWriteFailure(problem);
    }
  }

  /** The two pages of 2.5 that replace the list of posts: "Invite people" (for the owner) and "Your account". */
  const panel =
    session === null ? null : view.kind === "invite" && profile?.is_owner ? "invite" : view.kind === "account" ? "account" : null;

  /** True on a board that shows nothing to visitors who are not signed in. */
  const closedToMe = session === null && server?.board === "closed";
  /** Why the composer is switched off, or null. */
  const postingBlocked = profile !== null && !profile.can_post ? "Confirm your email address to post. The link is in your inbox." : null;
  const showingMentions = view.kind === "mentions" && session !== null;
  const shown = showingMentions ? mentions : messages;
  const viewedName = viewedAccountId === null ? null : (names.get(viewedAccountId) ?? `user ${viewedAccountId}`);
  const viewingSelf = session !== null && viewedAccountId === session.account_id;
  const heading =
    panel === "invite"
      ? "Invite people"
      : panel === "account"
        ? "Your account"
        : showingMentions
          ? "Mentions of you"
          : closedToMe
            ? "Members only"
            : session === null && server === null
              ? "Brief!" // not known yet whether a visitor may read here: promise nothing
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

        {session && resetSecret === null ? (
          <section className="me" aria-label="Your account">
            <p className="me-label">Signed in as</p>
            <p className="me-name">{session.username}</p>
            {profile?.email && (
              <p className="me-email">
                {profile.email}
                {!profile.email_verified && " (not confirmed)"}
              </p>
            )}
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
              {profile?.is_owner && (
                <button type="button" className="btn btn-quiet" onClick={() => go({ kind: "invite" })}>
                  Invite people
                </button>
              )}
              {profile && (
                <button type="button" className="btn btn-quiet" onClick={() => go({ kind: "account" })}>
                  Your account
                </button>
              )}
              <button type="button" className="btn btn-quiet" onClick={() => void signOut()}>
                Sign out
              </button>
            </div>
          </section>
        ) : (
          <AuthPanel
            key={invitation?.secret ?? "no-invitation"}
            invitation={invitation}
            onSignedIn={signedIn}
            takenUsernames={loadState === "ready" && !closedToMe ? takenUsernames : null}
            server={server}
            resetSecret={resetSecret}
            onResetClosed={resetClosed}
          />
        )}

        <p className="status" data-state={loadState}>
          <span className="status-dot" aria-hidden="true" />
          {loadState === "offline"
            ? "Server not reachable"
            : loadState === "loading"
              ? "Connecting…"
              : closedToMe
                ? "Server online"
                : `Server online, ${accounts.length} ${accounts.length === 1 ? "member" : "members"}`}
        </p>
        <p className="rail-links">
          <a href="/docs/index.html">API docs</a>
          <a href="/terms.html">Terms</a>
          <a href="/privacy.html">Privacy</a>
          {/* 2.6: what Brief! is, how to use it, pricing, FAQ and refunds, as plain pages like Terms and Privacy. */}
          <a href="/help/index.html">Help</a>
        </p>
        {!session && (
          <p className="rail-also">
            Also from OneTechly:{" "}
            <a href="https://pixelperfectapi.net" target="_blank" rel="noopener noreferrer">
              PixelPerfect
            </a>
            , a screenshot API for developers.
          </p>
        )}
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
            {!closedToMe && (
              <button
                type="button"
                className="btn btn-quiet"
                onClick={() => {
                  void refresh();
                  void loadMentions();
                  void loadProfile();
                  setInviteTick((tick) => tick + 1);
                }}
              >
                Refresh
              </button>
            )}
          </div>
        </header>

        {pageNotice && (
          <p className="page-notice" data-tone={pageNotice.tone} role="status">
            <span>{pageNotice.text}</span>
            <button type="button" className="link" onClick={() => setPageNotice(null)}>
              Dismiss
            </button>
          </p>
        )}

        {session && panel === "invite" ? (
          <InvitePanel session={session} refreshKey={inviteTick} onSessionEnded={() => endSession("Your session ended. Sign in again.")} />
        ) : session && panel === "account" ? (
          <AccountPanel
            session={session}
            profile={profile}
            onDeleted={() => {
              go({ kind: "all" });
              endSession("Your account and your posts were deleted.");
            }}
            onSessionEnded={() => endSession("Your session ended. Sign in again.")}
          />
        ) : (
          <>
            {session && profile && (
              <EmailStatus
                key={session.account_id}
                session={session}
                profile={profile}
                onProfile={setProfile}
                onSessionEnded={() => endSession("Your session ended. Sign in again.")}
                onReload={loadProfile}
              />
            )}

            {session ? (
              // While the address waits for confirmation, the "Check your email" step above takes the composer's place.
              postingBlocked === null && <Composer username={session.username} onPost={post} blocked={postingBlocked} />
            ) : closedToMe || server === null ? null : (
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
              <p className="quiet">{session === null && server === null ? "Connecting…" : "Loading posts…"}</p>
            ) : closedToMe ? (
              <div className="notice">
                <p className="notice-title">This board is for invited members.</p>
                <p>Sign in to read and post. To join, ask the person who runs this board to invite you.</p>
              </div>
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
                    <button
                      type="button"
                      className="btn btn-quiet"
                      disabled={loadingOlderMentions}
                      onClick={() => void loadOlderMentions()}
                    >
                      {loadingOlderMentions ? "Loading…" : "Show older mentions"}
                    </button>
                  </p>
                )}
              </>
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
