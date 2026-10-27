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
  /** Re-reads the account: used when the server's answer suggests the screen is out of date. Resolves to the account, or null if it couldn't be read. */
  onReload: () => Promise<Profile | null>;
}

/** "Not now" on the request for an address lasts for this visit: until the tab or the app is closed. */
const LATER_KEY = "brief.emailLater";

/** Which of the three versions of the panel is showing. */
type Step = "add" | "check" | "bounced";

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
 *   Mail bounced (2.7)   Resend reported that the mail could not be delivered. It says so, in
 *                        place of "Check your email", and asks for a different address; sending
 *                        again would only bounce again, so that is not offered.
 */
export function EmailStatus({ session, profile, onProfile, onSessionEnded, onReload }: Props) {
  const [email, setEmail] = useState("");
  const [editing, setEditing] = useState(false);
  const [later, setLater] = useState(() => askedForLater(session.account_id));
  const [busy, setBusy] = useState(false);
  /**
   * The line under the panel: what the last action did. 2.7.1: each note belongs to the step it was
   * written for, and shows only while that step is on screen. Before, "Sent again" stayed under
   * "We couldn't deliver your link" when the bounce report arrived after the send.
   */
  const [note, setNote] = useState<{
    text: string;
    tone: "good" | "bad" | "info";
    step: Step;
  } | null>(null);

  const hasAddress = profile.email !== null;
  const bounced = hasAddress && profile.email_bounced === true;
  const step: Step = bounced ? "bounced" : hasAddress ? "check" : "add";
  if (hasAddress && profile.email_verified) return null;
  if (!hasAddress && later) return null;

  function failed(problem: unknown, otherwise: string) {
    if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
    else if (problem instanceof NetworkError)
      setNote({
        text: "The server isn't responding. Try again in a moment.",
        tone: "bad",
        step,
      });
    else setNote({ text: otherwise, tone: "bad", step });
  }

  async function saveAddress() {
    const address = email.trim();
    if (!looksLikeEmail(address)) {
      setNote({
        text: "Enter an email address like name@example.com.",
        tone: "bad",
        step,
      });
      return;
    }
    setBusy(true);
    setNote(null);
    try {
      onProfile(await api.setEmail(session, address));
      setEditing(false);
      setEmail("");
      setNote({ text: "Saved. The link is on its way.", tone: "good", step: "check" });
    } catch (problem) {
      if (problem instanceof ApiError && problem.status === 422)
        setNote({
          text: "Mail to that address was refused less than a day ago. Check the spelling, or use another address.",
          tone: "bad",
          step,
        });
      else failed(problem, "That address can't be used: another account has it, or it isn't a valid address.");
    } finally {
      setBusy(false);
    }
  }

  async function sendAgain() {
    setBusy(true);
    setNote(null);
    try {
      await api.resendVerification(session.token);
      setNote({
        text: "Sent again. Only the newest link works.",
        tone: "good",
        step: "check",
      });
    } catch (problem) {
      if (problem instanceof ApiError && problem.status === 429) {
        setNote({
          text: "A link was sent less than a minute ago. Give it a moment, then try again.",
          tone: "bad",
          step,
        });
      } else if (problem instanceof ApiError && problem.status === 422) {
        void onReload(); // 2.7: the mail bounced in the meantime; the panel changes to say so
      } else if (problem instanceof ApiError && problem.status === 400) {
        void onReload(); // nothing is waiting to be confirmed any more: the address was confirmed somewhere else
      } else {
        failed(problem, "That didn't work. Try again in a moment.");
      }
    } finally {
      setBusy(false);
    }
  }

  /**
   * "I've opened the link" (2.7.1). The panel also checks by itself whenever this tab comes back
   * into view, but the button must always answer: before, when the address wasn't confirmed yet,
   * pressing it changed nothing on screen and looked broken.
   */
  async function checkConfirmed() {
    setBusy(true);
    setNote(null);
    try {
      const now = await onReload();
      if (now === null) {
        setNote({ text: "The server isn't responding. Try again in a moment.", tone: "bad", step });
      } else if (!now.email_verified && !now.email_bounced) {
        // Confirmed or bounced: the panel changes or closes by itself, so nothing needs saying.
        setNote({
          text: "Not confirmed yet. Open the link in the mail we sent, then come back to this page: it updates by itself.",
          tone: "info",
          step: "check",
        });
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

  const showForm = !hasAddress || editing || bounced;

  return (
    <section
      className={bounced ? "banner banner-check banner-bounced" : hasAddress ? "banner banner-check" : "banner"}
      aria-label="Your email address"
    >
      {bounced ? (
        <>
          <svg className="check-icon" viewBox="0 0 48 48" aria-hidden="true">
            <rect x="5" y="11" width="38" height="27" rx="5" fill="none" stroke="currentColor" strokeWidth="3" />
            <path d="M17 19l14 14M31 19L17 33" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
          </svg>
          <p className="banner-title">We couldn't deliver your link</p>
          <p>
            Mail to <strong>{profile.email}</strong> was refused: the address may be mistyped, or the mailbox may not exist. Enter the
            address again, and we'll send a new link to it. If you're sure it was right, you can try it again after a day.
          </p>
        </>
      ) : hasAddress ? (
        <>
          {/* 2.5: a full step of its own, in place of the post box, rather than a strip above it. */}
          <svg className="check-icon" viewBox="0 0 48 48" aria-hidden="true">
            <rect x="5" y="11" width="38" height="27" rx="5" fill="none" stroke="currentColor" strokeWidth="3" />
            <path d="M7 14l17 13 17-13" fill="none" stroke="currentColor" strokeWidth="3" strokeLinejoin="round" />
          </svg>
          <p className="banner-title">Check your email</p>
          <p>
            We sent a link to <strong>{profile.email}</strong>. Open it to confirm the address, and you can post. Until then you can read
            everything here. The link works once, for 24 hours.
          </p>
          <p className="field-note">Nothing after a few minutes? Look in the spam folder, or send the link again.</p>
        </>
      ) : (
        <>
          <p className="banner-title">Add an email address</p>
          <p>
            It is your way back in if you forget your password, and other members never see it. We'll mail you a link to confirm it; posting
            waits until you have opened that link.
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
          {!bounced && (
            <button type="button" className="link" onClick={hasAddress ? () => setEditing(false) : notNow}>
              {hasAddress ? "Cancel" : "Not now"}
            </button>
          )}
        </form>
      ) : (
        <p className="banner-actions">
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void checkConfirmed()}>
            {busy ? "Checking…" : "I've opened the link"}
          </button>
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

      {note && note.step === step && (
        <p className="field-note" data-tone={note.tone} role="status">
          {note.text}
        </p>
      )}
    </section>
  );
}
