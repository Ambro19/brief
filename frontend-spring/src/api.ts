import type { Account, Message, MessagePage, Profile, PublicAccount, ServerInfo, Session } from "./types";

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
    const body = (await jsonOrNull<{ password_policy?: string; email?: string; board?: string }>(await request("GET", "/health"))) ?? {};
    return {
      passwordPolicy: body.password_policy === "strong" ? "strong" : "basic",
      email: body.email === "required" ? "required" : body.email === "optional" ? "optional" : "none",
      board: body.board === "closed" ? "closed" : "open",
    };
  },

  /**
   * Creates an account. The email address and the invitation code are sent only when given:
   * an older server refuses a request with fields it does not know.
   */
  async register(username: string, password: string, email?: string, inviteCode?: string): Promise<void> {
    const body: Record<string, string> = { username, password };
    if (email) body.email = email;
    if (inviteCode) body.invite_code = inviteCode;
    await request("POST", "/register", body);
  },

  async login(username: string, password: string): Promise<Session> {
    const response = await request("POST", "/login", { username, password });
    const account = (await response.json()) as Account;
    const token = response.headers.get(TOKEN_HEADER) ?? "";
    return { account_id: account.account_id, username: account.username, token };
  },

  async logout(token: string): Promise<void> {
    await request("POST", "/logout", undefined, token);
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
