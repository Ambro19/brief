import type {
  Account,
  InvitationInfo,
  InvitationView,
  Message,
  MessagePage,
  Profile,
  PublicAccount,
  PushSubscriptionJson,
  ServerInfo,
  Session,
  SignedInSession,
} from "./types";

/**
 * Thin client for the Java API.
 *
 * The API answers errors with a status code and an empty body, so this client
 * turns the status into an ApiError and lets the screen decide what to say.
 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    /** Seconds to wait before trying again, when the server said so (429 Too Many Requests). */
    public readonly retryAfterSeconds: number | null = null,
  ) {
    super(`Request failed with status ${status}`);
  }
}

/** The server could not be reached at all (not started, network down). */
export class NetworkError extends Error {
  constructor() {
    super("The server could not be reached");
  }
}

const TOKEN_HEADER = "X-Auth-Token";
const HAS_MORE_HEADER = "X-Has-More";

async function request(method: string, path: string, body?: unknown, token?: string): Promise<Response> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers["Authorization"] = `Bearer ${token}`;

  let response: Response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new NetworkError();
  }
  // A dev proxy answers 502/504 when the Java server is not running.
  if (response.status === 502 || response.status === 503 || response.status === 504) throw new NetworkError();
  if (!response.ok) {
    const retryAfter = Number(response.headers.get("Retry-After"));
    throw new ApiError(response.status, retryAfter > 0 ? retryAfter : null);
  }
  return response;
}

/** Reads a JSON body; an empty body (the API's way of saying "nothing there") becomes null. */
async function jsonOrNull<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : null;
}

export const api = {
  /**
   * What kind of server this is (see ServerInfo in types.ts). Throws NetworkError if it cannot
   * be reached. A server from before 2.4 does not mention email or the board; that reads as
   * "none" and "open", and the app then behaves exactly as 2.3 did.
   */
  async serverInfo(): Promise<ServerInfo> {
    const body =
      (await jsonOrNull<{ password_policy?: string; email?: string; board?: string; sessions?: string; push?: string }>(
        await request("GET", "/health"),
      )) ?? {};
    return {
      passwordPolicy: body.password_policy === "strong" ? "strong" : "basic",
      email: body.email === "required" ? "required" : body.email === "optional" ? "optional" : "none",
      board: body.board === "closed" ? "closed" : "open",
      sessions: body.sessions === "kept",
      push: body.push === "on",
    };
  },

  /**
   * Creates an account. The email address and the invitation are sent only when given, so a
   * request to an older server carries only the fields it knows.
   */
  async register(username: string, password: string, email?: string, invitation?: string): Promise<void> {
    const body: Record<string, string> = { username, password };
    if (email) body.email = email;
    if (invitation) body.invitation = invitation;
    await request("POST", "/register", body);
  },

  /** What an invitation link says: who sent it, to which address. 400 if it no longer works (2.5). */
  async lookupInvitation(secret: string): Promise<InvitationInfo> {
    return (await request("POST", "/invitations/lookup", { token: secret })).json();
  },

  /** The board owner mails an invitation (2.5). */
  async inviteByEmail(token: string, email: string): Promise<InvitationView> {
    return (await request("POST", "/invitations", { email }, token)).json();
  },

  /** The board owner makes a link to pass on; the answer holds it, this once (2.5). */
  async createInvitationLink(token: string): Promise<{ link: string; invitation: InvitationView }> {
    return (await request("POST", "/invitations/link", undefined, token)).json();
  },

  /** Everything the board owner sent, newest first (2.5). */
  async invitations(token: string): Promise<InvitationView[]> {
    return (await request("GET", "/invitations", undefined, token)).json();
  },

  /** Withdraws an unused invitation (2.5). */
  async withdrawInvitation(token: string, invitationId: number): Promise<void> {
    await request("DELETE", `/invitations/${invitationId}`, undefined, token);
  },

  /** Deletes the signed-in member's account and posts; the password is asked again (2.5). */
  async deleteAccount(session: Session, password: string): Promise<void> {
    await request("POST", `/accounts/${session.account_id}/deletion`, { password }, session.token);
  },

  /**
   * Signs in. `stay` (2.8) asks for a 30-day session; it is sent only when true, and only to a
   * server that keeps sessions, so older servers get exactly the two fields they know.
   */
  async login(username: string, password: string, stay = false): Promise<Session> {
    const response = await request("POST", "/login", stay ? { username, password, stay: true } : { username, password });
    const account = (await response.json()) as Account;
    const token = response.headers.get(TOKEN_HEADER) ?? "";
    return { account_id: account.account_id, username: account.username, token };
  },

  async logout(token: string): Promise<void> {
    await request("POST", "/logout", undefined, token);
  },

  /** Where the member is signed in, newest first (2.8). */
  async sessions(token: string): Promise<SignedInSession[]> {
    return (await request("GET", "/sessions", undefined, token)).json();
  },

  /** Signs one of the member's other devices out (2.8). 404 if it is not theirs or already gone. */
  async endSession(token: string, sessionId: number): Promise<void> {
    await request("DELETE", `/sessions/${sessionId}`, undefined, token);
  },

  /**
   * Signs out everywhere but here (2.8). The server also retires this device's token and answers
   * with a new one for the same session; the caller must keep using that one.
   */
  async signOutOthers(token: string): Promise<{ ended: number; token: string }> {
    const response = await request("POST", "/sessions/sign-out-others", undefined, token);
    const body = (await response.json()) as { ended: number };
    return { ended: body.ended, token: response.headers.get(TOKEN_HEADER) ?? token };
  },

  /** The server's public key for notifications (2.8); 404 when they are off. */
  async pushKey(): Promise<string> {
    return ((await (await request("GET", "/push/key")).json()) as { public_key: string }).public_key;
  },

  async pushSubscribe(token: string, subscription: PushSubscriptionJson): Promise<void> {
    await request("POST", "/push/subscription", subscription, token);
  },

  /** Whether the server has this browser's subscription for this session. */
  async pushCheck(token: string, endpoint: string): Promise<boolean> {
    return ((await (await request("POST", "/push/subscription/check", { endpoint }, token)).json()) as { on: boolean }).on;
  },

  async pushUnsubscribe(token: string, endpoint: string): Promise<void> {
    await request("POST", "/push/subscription/deletion", { endpoint }, token);
  },

  /** A test notification to this device. 429 if one was sent less than a minute ago. */
  async pushTest(token: string): Promise<number> {
    return ((await (await request("POST", "/push/test", undefined, token)).json()) as { sent: number }).sent;
  },

  /** The signed-in member's own account, with the email address nobody else is shown. */
  async me(token: string): Promise<Profile> {
    return (await request("GET", "/me", undefined, token)).json();
  },

  /** Gives the account an address (or corrects one not confirmed yet). The server mails the confirmation link. */
  async setEmail(session: Session, email: string): Promise<Profile> {
    return (await request("POST", `/accounts/${session.account_id}/email`, { email }, session.token)).json();
  },

  /** Asks for the confirmation link to be mailed again. 429 if the last one is less than a minute old. */
  async resendVerification(token: string): Promise<void> {
    await request("POST", "/email/verification", undefined, token);
  },

  /** Uses the secret from a confirmation link. Needs no sign-in: holding the link is the proof. */
  async verifyEmail(secret: string): Promise<void> {
    await request("POST", "/email/verify", { token: secret });
  },

  /** "Forgot password?" The server answers the same whether or not the address has an account. */
  async forgotPassword(email: string): Promise<void> {
    await request("POST", "/password/forgot", { email });
  },

  /** Uses the secret from a password link to set a new password. */
  async resetPassword(secret: string, password: string): Promise<void> {
    await request("POST", "/password/reset", { token: secret, password });
  },

  /*
   * The three lists below are public on an open board and need a login token on a closed one
   * (2.4). So whoever is signed in sends the token along; a visitor sends none, which is what
   * `undefined` means. An open board, and every older server, simply does not look at it.
   */

  async accounts(token?: string): Promise<PublicAccount[]> {
    return (await request("GET", "/accounts", undefined, token)).json();
  },

  /**
   * One page of posts, newest first: everyone's (accountId null) or one member's.
   * `before` is the id of the oldest post already on screen; leave it out for the newest page.
   */
  async messagesPage(accountId: number | null, limit: number, before?: number, token?: string): Promise<MessagePage> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (before !== undefined) query.set("before", String(before));
    const path = accountId === null ? "/messages" : `/accounts/${accountId}/messages`;

    const response = await request("GET", `${path}?${query}`, undefined, token);
    const items = (await response.json()) as Message[];
    // A server that pages always sends X-Has-More. One that does not (the Javalin edition)
    // ignores the query, sends every post, and sends no such header.
    const hasMore = response.headers.get(HAS_MORE_HEADER);
    return { items, hasMore: hasMore === "true", complete: hasMore === null };
  },

  /**
   * One page of the posts by other people that mention this account, newest first.
   * Pages exactly like messagesPage: `before` is the id of the oldest mention already on screen.
   */
  async mentionsPage(accountId: number, limit: number, before?: number, token?: string): Promise<MessagePage> {
    const query = new URLSearchParams({ limit: String(limit) });
    if (before !== undefined) query.set("before", String(before));

    const response = await request("GET", `/accounts/${accountId}/mentions?${query}`, undefined, token);
    const items = (await response.json()) as Message[];
    // A server from before 2.3 (and the Javalin edition) ignores the query, sends every mention,
    // and sends no X-Has-More header. That whole list is then simply the one and only page.
    const hasMore = response.headers.get(HAS_MORE_HEADER);
    return { items, hasMore: hasMore === "true", complete: hasMore === null };
  },

  async createMessage(session: Session, text: string): Promise<Message> {
    const body = { posted_by: session.account_id, message_text: text, time_posted_epoch: Math.floor(Date.now() / 1000) };
    return (await request("POST", "/messages", body, session.token)).json();
  },

  async updateMessage(session: Session, messageId: number, text: string): Promise<Message> {
    return (await request("PATCH", `/messages/${messageId}`, { message_text: text }, session.token)).json();
  },

  /** Resolves with the deleted message, or null if it was already gone. */
  async deleteMessage(session: Session, messageId: number): Promise<Message | null> {
    return jsonOrNull<Message>(await request("DELETE", `/messages/${messageId}`, undefined, session.token));
  },
};
