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

export const MAX_MESSAGE_LENGTH = 255;
