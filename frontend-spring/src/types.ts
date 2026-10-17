/** Shapes of the JSON the Java API sends and receives. Field names match the Java model classes. */

export interface Account {
  account_id: number;
  username: string;
  password: string;
}

export interface PublicAccount {
  account_id: number;
  username: string;
}

export interface Message {
  message_id: number;
  posted_by: number;
  message_text: string;
  /** Seconds since 1970-01-01 (not milliseconds). */
  time_posted_epoch: number;
}

/** Who is signed in on this browser tab. The password is never kept. */
export interface Session {
  account_id: number;
  username: string;
  token: string;
}

/**
 * One page of posts, newest first.
 * `complete` is true when the server does not page and sent the whole list (the Javalin edition).
 */
export interface MessagePage {
  items: Message[];
  hasMore: boolean;
  complete: boolean;
}

/** Which rule the server applies to new passwords. Read from GET /health. */
export type PasswordPolicy = "basic" | "strong";

/**
 * What the server says about itself in GET /health. The web app is served by three kinds of
 * server (the Javalin edition, Spring Boot before 2.4, Spring Boot 2.4), and this is how it
 * finds out which forms to show.
 *
 *   email   "required": a new account must give an email address (2.4, strict mode).
 *           "optional": it may (2.4, compat mode).
 *           "none":     this server knows nothing about email (older servers). No email field,
 *                       no "Forgot password?", and the account endpoints of 2.4 are never called.
 *   board   "closed":   creating an account needs an invitation code and reading needs a sign-in.
 *           "open":     anyone may read and join (also the answer for older servers).
 */
export interface ServerInfo {
  passwordPolicy: PasswordPolicy;
  email: "required" | "optional" | "none";
  board: "open" | "closed";
}

/** What a member may see of their own account (GET /me, 2.4). */
export interface Profile {
  account_id: number;
  username: string;
  /** null for an account created before 2.4 that has not given an address yet. */
  email: string | null;
  email_verified: boolean;
  /** False while an address is waiting to be confirmed. */
  can_post: boolean;
  /** True for the member who runs the board and can invite people (2.5). Missing from older servers. */
  is_owner?: boolean;
}

/** What an invitation link tells the person who opened it (POST /invitations/lookup, 2.5). */
export interface InvitationInfo {
  inviter: string;
  /** The address it was mailed to; null for a link that was passed on by other means. */
  email: string | null;
  expires_at: number;
}

/** An invitation as the board owner sees it in the list (GET /invitations, 2.5). */
export interface InvitationView {
  invitation_id: number;
  email: string | null;
  created_at: number;
  expires_at: number;
  status: "pending" | "used" | "expired";
  joined_as: string | null;
}

export const MAX_MESSAGE_LENGTH = 255;
