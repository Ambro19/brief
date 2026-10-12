import type { PublicAccount } from "../types";

/**
 * Turns the plain text of a post into pieces the screen can draw differently:
 * ordinary text, web links, and mentions of members.
 *
 * The post is never treated as HTML. Each piece is rendered by React as text or as a
 * specific element, so nothing a user types can inject markup or script.
 */
export type Token =
  | { kind: "text"; text: string }
  | { kind: "link"; text: string; href: string }
  | { kind: "mention"; text: string; accountId: number };

/**
 * Endings that mark a bare name such as "pixelperfectapi.net" as a web address (2.3).
 *
 * People write addresses without "https://" all the time, but a dot between two words is not
 * always an address: "report.pdf", "Node.js", "thanks.it was great". So a bare name only becomes a
 * link when it ends in one of these. The list is deliberately short. It leaves out endings that
 * are also file types (md, sh, py, rs, zip) and ones that are also everyday words (it, is, in,
 * to, so, me, no, be, at, us, my), where a wrong link is more likely than a right one. An address
 * with one of those endings still becomes a link when it is written with https:// or www.
 */
const DOMAIN_ENDINGS = [
  "com", "net", "org", "io", "dev", "app", "ai", "co", "edu", "gov", "info", "biz", "xyz", "tech", "online",
  "site", "store", "blog", "cloud", "page", "tv", "fm", "gg", "uk", "ca", "de", "fr", "eu", "au", "nl", "ch", "se",
  "jp", "br", "mx", "nz", "za", "sg", "kr", "cn", "ru", "pl", "ie", "fi", "dk", "cz", "pt", "gr", "tr", "il", "ae",
  "sa", "ng", "ke", "gh", "cd", "cm", "ci", "sn", "rw", "ug", "tz", "et", "eg", "ma", "dz", "tn",
].join("|");

/** One part of a host name between dots: letters, digits and inner dashes. */
const HOST_LABEL = "[a-z0-9](?:[a-z0-9-]*[a-z0-9])?";

/**
 * Two ways to recognise a web address, tried in this order at each position:
 *   1. it starts with http://, https:// or www. and runs until whitespace or a quote;
 *   2. it is a bare host name with a known ending, optionally followed by a port and a path.
 *      After the ending there must be no further letter, digit or dash ("example.community" is
 *      not "example.com"), and no dot followed by another letter.
 */
const URL_PATTERN = new RegExp(
  "\\b(?:https?:\\/\\/|www\\.)[^\\s<>\"'`]+" +
    "|" +
    `(?:${HOST_LABEL}\\.)+(?:${DOMAIN_ENDINGS})(?![a-z0-9-])(?!\\.[a-z0-9])(?::\\d{2,5})?(?:[/?#][^\\s<>"'\`]*)?`,
  "gi",
);

/** Punctuation that usually ends the sentence rather than the address. */
const TRAILING_PUNCTUATION = /[.,;:!?)\]}]+$/;

/** True if the match was found by the first rule above (it names its scheme, or starts with www.). */
function namesItsScheme(candidate: string): boolean {
  return /^(?:https?:\/\/|www\.)/i.test(candidate);
}

/**
 * A character just before a bare name that shows it is part of something else: the rest of a
 * word, an email address (name@example.com), or a longer dotted or slashed path.
 * (Checked in code, not with a look-behind in the pattern: older iPhones cannot read those.)
 */
function continuesSomethingElse(before: string | undefined): boolean {
  return before !== undefined && /[\p{L}\p{N}_@.\-/]/u.test(before);
}

/** The address to open, or null if the text is not a usable http(s) address. */
function toHref(candidate: string): string | null {
  const withScheme = /^https?:\/\//i.test(candidate) ? candidate : `https://${candidate}`;
  try {
    const url = new URL(withScheme);
    const isWeb = url.protocol === "http:" || url.protocol === "https:";
    const hasRealHost = url.hostname === "localhost" || url.hostname.includes(".");
    // "https://bank.example@elsewhere.example/" opens elsewhere.example: everything before the @ is
    // a user name, not the site. Such an address reads as one place and leads to another, so it is not linked.
    const namesAUser = url.username !== "" || url.password !== "";
    return isWeb && hasRealHost && !namesAUser ? url.href : null;
  } catch {
    return null;
  }
}

/** Letters, digits and underscore can be part of a name. Mirrors MentionService.java. */
function isNameCharacter(ch: string | undefined): boolean {
  return ch !== undefined && /[\p{L}\p{N}_]/u.test(ch);
}

/** True if the text contains the username as a whole word, in any letter case. Same rule as the server. */
export function mentionsName(text: string, username: string): boolean {
  const haystack = text.toLowerCase();
  const needle = username.trim().toLowerCase();
  if (!needle) return false;
  let from = 0;
  for (;;) {
    const start = haystack.indexOf(needle, from);
    if (start < 0) return false;
    const end = start + needle.length;
    if (!isNameCharacter(haystack[start - 1]) && !isNameCharacter(haystack[end])) return true;
    from = start + 1;
  }
}

/** Splits plain text into text and mention tokens. */
function splitMentions(text: string, accounts: PublicAccount[]): Token[] {
  const lower = text.toLowerCase();
  // A few characters change length when lower-cased; positions would no longer line up, so skip those.
  if (accounts.length === 0 || lower.length !== text.length) return text ? [{ kind: "text", text }] : [];

  // Longest names first, so "TestUser10" wins over "TestUser1" at the same position.
  const names = accounts
    .map((account) => ({ id: account.account_id, lower: account.username.toLowerCase() }))
    .filter((name) => name.lower.trim().length > 0)
    .sort((a, b) => b.lower.length - a.lower.length);

  const tokens: Token[] = [];
  let plainStart = 0;
  let i = 0;
  while (i < text.length) {
    const found = isNameCharacter(lower[i - 1])
      ? undefined
      : names.find((name) => lower.startsWith(name.lower, i) && !isNameCharacter(lower[i + name.lower.length]));
    if (!found) {
      i += 1;
      continue;
    }
    // Include a leading @ in the highlighted piece, if the writer typed one.
    const start = i > plainStart && text[i - 1] === "@" ? i - 1 : i;
    const end = i + found.lower.length;
    if (start > plainStart) tokens.push({ kind: "text", text: text.slice(plainStart, start) });
    tokens.push({ kind: "mention", text: text.slice(start, end), accountId: found.id });
    plainStart = end;
    i = end;
  }
  if (plainStart < text.length) tokens.push({ kind: "text", text: text.slice(plainStart) });
  return tokens;
}

/** Splits a post into text, link and mention tokens, in reading order. */
export function tokenize(text: string, accounts: PublicAccount[]): Token[] {
  const tokens: Token[] = [];
  let last = 0;
  for (const match of text.matchAll(URL_PATTERN)) {
    const start = match.index ?? 0;
    const address = match[0].replace(TRAILING_PUNCTUATION, "");
    if (!namesItsScheme(address) && continuesSomethingElse(text[start - 1])) continue;
    const href = toHref(address);
    if (!href) continue; // not a real address: leave it as ordinary text
    tokens.push(...splitMentions(text.slice(last, start), accounts));
    tokens.push({ kind: "link", text: address, href });
    last = start + address.length;
  }
  tokens.push(...splitMentions(text.slice(last), accounts));
  return tokens;
}
