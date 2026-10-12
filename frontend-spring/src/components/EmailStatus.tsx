import { useState } from "react";
import { ApiError, NetworkError, api } from "../api";
import { looksLikeEmail } from "../lib/links";
import type { Profile, Session } from "../types";

interface Props {
  session: Session;
  profile: Profile;
  /** The server answered with the account as it now is. */
  onProfile: (profile: Profile) => void;
  /** The server no longer accepts this session (401). */
  onSessionEnded: () => void;
  /** Re-reads the account: used when the server's answer suggests the screen is out of date. */
  onReload: () => void;
}

/** "Not now" on the request for an address lasts for this visit: until the tab or the app is closed. */
const LATER_KEY = "brief.emailLater";

function askedForLater(accountId: number): boolean {
  try {
    return window.sessionStorage.getItem(LATER_KEY) === String(accountId);
  } catch {
    return false;
  }
}

/**
 * The panel above the composer that deals with the member's email address (2.4). It shows in
 * two situations and is absent otherwise:
 *
 *   No address yet       An account from before 2.4. It asks for one, and can be put off.
 *   Address unconfirmed  It says where the link went, and offers to send it again or to
 *                        correct the address. Posting waits until the link has been opened.
 */
export function EmailStatus({ session, profile, onProfile, onSessionEnded, onReload }: Props) {
  const [email, setEmail] = useState("");
  const [editing, setEditing] = useState(false);
  const [later, setLater] = useState(() => askedForLater(session.account_id));
  const [busy, setBusy] = useState(false);
  /** The line under the panel: what the last action did. */
  const [note, setNote] = useState<{ text: string; tone: "good" | "bad" } | null>(null);

  const hasAddress = profile.email !== null;
  if (hasAddress && profile.email_verified) return null;
  if (!hasAddress && later) return null;

  function failed(problem: unknown, otherwise: string) {
    if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
    else if (problem instanceof NetworkError) setNote({ text: "The server isn't responding. Try again in a moment.", tone: "bad" });
    else setNote({ text: otherwise, tone: "bad" });
  }

  async function saveAddress() {
    const address = email.trim();
    if (!looksLikeEmail(address)) {
      setNote({ text: "Enter an email address like name@example.com.", tone: "bad" });
      return;
    }
    setBusy(true);
    setNote(null);
    try {
      onProfile(await api.setEmail(session, address));
      setEditing(false);
      setEmail("");
      setNote({ text: "Saved. The link is on its way.", tone: "good" });
    } catch (problem) {
      failed(problem, "That address can't be used: another account has it, or it isn't a valid address.");
    } finally {
      setBusy(false);
    }
  }

  async function sendAgain() {
    setBusy(true);
    setNote(null);
    try {
      await api.resendVerification(session.token);
      setNote({ text: "Sent again. Only the newest link works.", tone: "good" });
    } catch (problem) {
      if (problem instanceof ApiError && problem.status === 429) {
        setNote({ text: "A link was sent less than a minute ago. Give it a moment, then try again.", tone: "bad" });
      } else if (problem instanceof ApiError && problem.status === 400) {
        onReload(); // nothing is waiting to be confirmed any more: the address was confirmed somewhere else
      } else {
        failed(problem, "That didn't work. Try again in a moment.");
      }
    } finally {
      setBusy(false);
    }
  }

  function notNow() {
    setLater(true);
    try {
      window.sessionStorage.setItem(LATER_KEY, String(session.account_id));
    } catch {
      /* then it is asked again after a reload */
    }
  }

  const showForm = !hasAddress || editing;

  return (
    <section className="banner" aria-label="Your email address">
      {hasAddress ? (
        <>
          <p className="banner-title">Confirm your email address</p>
          <p>
            We mailed a link to <strong>{profile.email}</strong>. Until you open it you can read, but not post. The link works once, for 24 hours.
          </p>
        </>
      ) : (
        <>
          <p className="banner-title">Add an email address</p>
          <p>
            It is your way back in if you forget your password, and other members never see it. We'll mail you a link to confirm it; posting waits
            until you have opened that link.
          </p>
        </>
      )}

      {showForm ? (
        <form
          className="banner-form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void saveAddress();
          }}
        >
          <label className="visually-hidden" htmlFor="banner-email">
            Email address
          </label>
          <input
            id="banner-email"
            type="email"
            inputMode="email"
            autoComplete="email"
            autoCapitalize="none"
            spellCheck={false}
            maxLength={254}
            placeholder="name@example.com"
            value={email}
            onChange={(event) => {
              setEmail(event.target.value);
              setNote(null);
            }}
          />
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? "Saving…" : hasAddress ? "Save and send link" : "Save"}
          </button>
          <button type="button" className="link" onClick={hasAddress ? () => setEditing(false) : notNow}>
            {hasAddress ? "Cancel" : "Not now"}
          </button>
        </form>
      ) : (
        <p className="banner-actions">
          <button type="button" className="link" disabled={busy} onClick={() => void sendAgain()}>
            Send the link again
          </button>
          <button
            type="button"
            className="link"
            onClick={() => {
              setEditing(true);
              setNote(null);
            }}
          >
            Use a different address
          </button>
        </p>
      )}

      {note && (
        <p className="field-note" data-tone={note.tone} role="status">
          {note.text}
        </p>
      )}
    </section>
  );
}
