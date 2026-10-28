import type { Session } from "../types";

const KEY = "brief.session";
const STAY_KEY = "brief.staySignedIn";

/**
 * Where the session is kept, and for how long.
 *
 *   sessionStorage (the default): survives a page reload, ends when the tab closes.
 *   localStorage ("Stay signed in"): survives closing the tab or the installed app.
 *
 * Either way the server decides how long the session lasts. Since 2.8, with "Stay signed in" the
 * server keeps it for 30 days from the last use (it used to end after 12 hours whatever was
 * chosen); without, 12 hours. After that, the next request asks you to sign in again.
 *
 * Storage can be unavailable (private windows, blocked site data), so every access is guarded
 * and the app still works without it.
 */
function read(storage: Storage): Session | null {
  const raw = storage.getItem(KEY);
  if (!raw) return null;
  const parsed = JSON.parse(raw) as Partial<Session>;
  if (typeof parsed.account_id === "number" && typeof parsed.username === "string" && typeof parsed.token === "string") {
    return { account_id: parsed.account_id, username: parsed.username, token: parsed.token };
  }
  return null;
}

export function loadSession(): Session | null {
  try {
    return read(window.sessionStorage) ?? read(window.localStorage);
  } catch {
    return null;
  }
}

/** Stores the session (or forgets it, for null). `stay` chooses the storage that outlives the tab. */
export function saveSession(session: Session | null, stay = false): void {
  try {
    // Clear both first, so signing out, or signing in without "stay", leaves nothing behind.
    window.sessionStorage.removeItem(KEY);
    window.localStorage.removeItem(KEY);
    if (session) (stay ? window.localStorage : window.sessionStorage).setItem(KEY, JSON.stringify(session));
  } catch {
    /* the app keeps the session in memory regardless */
  }
}

/** True when the session on this device was kept with "Stay signed in" (in localStorage, 2.8: notifications need it). */
export function isStayingSignedIn(): boolean {
  try {
    return read(window.localStorage) !== null;
  } catch {
    return false;
  }
}

/** The key the session is stored under, for code that listens to changes made in other tabs. */
export const SESSION_KEY = KEY;

/**
 * Replaces the token of the stored session, wherever it is kept (2.8: "Sign out everywhere else"
 * gives this device a new token for the same session).
 */
export function replaceStoredToken(token: string): void {
  try {
    for (const storage of [window.sessionStorage, window.localStorage]) {
      const stored = read(storage);
      if (stored) storage.setItem(KEY, JSON.stringify({ ...stored, token }));
    }
  } catch {
    /* the app keeps the session in memory regardless */
  }
}

/** True when Brief! was opened from a home-screen or desktop icon, in its own window. */
export function isInstalledApp(): boolean {
  try {
    const iosStandalone = (window.navigator as Navigator & { standalone?: boolean }).standalone === true;
    return iosStandalone || window.matchMedia("(display-mode: standalone)").matches;
  } catch {
    return false;
  }
}

/**
 * Whether the "Stay signed in" box starts ticked: what this person chose last time, or, with no
 * choice made yet, yes in the installed app (where closing and reopening is the normal way to
 * use it) and no in a browser tab.
 */
export function loadStayChoice(): boolean {
  try {
    const stored = window.localStorage.getItem(STAY_KEY);
    if (stored === "yes") return true;
    if (stored === "no") return false;
  } catch {
    /* fall through to the default */
  }
  return isInstalledApp();
}

export function saveStayChoice(stay: boolean): void {
  try {
    window.localStorage.setItem(STAY_KEY, stay ? "yes" : "no");
  } catch {
    /* the choice then simply is not remembered */
  }
}

/**
 * The newest mention this account has already looked at (a message id; 0 = none yet).
 * Kept in localStorage, so it survives closing the tab: a mention you have read stays read.
 */
export function loadSeenMentionId(accountId: number): number {
  try {
    const value = Number(window.localStorage.getItem(`brief.mentionsSeen.${accountId}`));
    return Number.isFinite(value) && value > 0 ? value : 0;
  } catch {
    return 0;
  }
}

export function saveSeenMentionId(accountId: number, messageId: number): void {
  try {
    window.localStorage.setItem(`brief.mentionsSeen.${accountId}`, String(messageId));
  } catch {
    /* without storage, mentions simply show as new again after a reload */
  }
}
