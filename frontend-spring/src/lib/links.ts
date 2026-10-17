/**
 * Links that arrive by email (2.4).
 *
 * The server mails three kinds of link, and all open this web app with a one-time secret in
 * the address:
 *
 *     https://briefmessage.net/?verify=<secret>     confirm an email address
 *     https://briefmessage.net/?reset=<secret>      choose a new password
 *     https://briefmessage.net/?invite=<secret>     create an account on a closed board (2.5)
 *
 * The app reads the secret, takes it out of the address bar at once (so it is not left in the
 * browser's history, a bookmark or a screenshot), and sends it to the API. Opening the link
 * does nothing by itself on the server: a mail program that fetches links to check them for
 * safety does not use the link up.
 */
export interface MailedLink {
  kind: "verify" | "reset" | "invite";
  secret: string;
}

/** What a secret looks like: letters, digits, "-" and "_". Anything else in the address is not one of ours. */
const SECRET = /^[A-Za-z0-9_-]{20,200}$/;

/** Reads a mailed link out of the page address and removes it from the address bar. Null if there is none. */
export function takeMailedLink(): MailedLink | null {
  let found: MailedLink | null = null;
  try {
    const query = new URLSearchParams(window.location.search);
    for (const kind of ["verify", "reset", "invite"] as const) {
      const secret = query.get(kind);
      if (secret !== null) {
        if (found === null && SECRET.test(secret)) found = { kind, secret };
        query.delete(kind);
      }
    }
    const rest = query.toString();
    const cleaned = window.location.pathname + (rest ? `?${rest}` : "") + window.location.hash;
    if (cleaned !== window.location.pathname + window.location.search + window.location.hash) {
      window.history.replaceState(null, "", cleaned);
    }
  } catch {
    /* an address that cannot be read holds no link */
  }
  return found;
}

/** Mirrors EmailPolicy.java closely enough to catch slips before the form is sent; the server decides. */
export function looksLikeEmail(text: string): boolean {
  const address = text.trim();
  if (address.length > 254 || address.includes("..")) return false;
  return /^[^\s@<>,;"\\]+@[^\s@<>,;"\\]+\.[^\s@<>,;"\\.]+$/.test(address);
}
