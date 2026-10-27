import { useEffect, useState } from "react";
import { ApiError, NetworkError, api } from "../api";
import { looksLikeEmail } from "../lib/links";
import { relativeTime } from "../lib/time";
import type { InvitationView, Session } from "../types";

interface Props {
  session: Session;
  /** 2.7: changes on Refresh and every 15 seconds; the list of sent invitations is read again, so "Bounced" appears without reopening the page. */
  refreshKey?: number;
  /** The server no longer accepts this session (401). */
  onSessionEnded: () => void;
}

const STATUS_TEXT: Record<InvitationView["status"], string> = {
  pending: "Waiting",
  used: "Joined",
  expired: "Expired",
  // 2.7: Resend reported that the mail could not be delivered; the address is probably mistyped.
  bounced: "Bounced: check the address",
  // 2.7: the address now belongs to a member, so this invitation can no longer create an account.
  taken: "Already a member",
};

/** Unused invitations that can still be withdrawn (the server allows it for every unused one). */
const WITHDRAWABLE: ReadonlyArray<InvitationView["status"]> = ["pending", "bounced", "taken"];

/**
 * "Invite people" (2.5), for the member who runs the board.
 *
 * Two ways to invite:
 *   by email   Brief! mails the link. The new member's address is confirmed by opening it.
 *   by link    Brief! shows a link once, to pass on in a text message or in person. The new
 *              member gives an address when joining and confirms it as usual.
 * Below them, everything sent so far; an invitation that is still waiting can be withdrawn.
 */
export function InvitePanel({ session, refreshKey = 0, onSessionEnded }: Props) {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{
    text: string;
    tone: "good" | "bad";
  } | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [sent, setSent] = useState<InvitationView[] | null>(null);
  /** How long an invitation works, as the server sets it; 7 days until one has been seen. */
  const days = sent && sent.length > 0 ? Math.round((sent[0].expires_at - sent[0].created_at) / 86400) : 7;
  const lifetime = days === 1 ? "one day" : `${days} days`;

  async function reload() {
    try {
      setSent(await api.invitations(session.token));
    } catch (problem) {
      if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
    }
  }

  useEffect(() => {
    void reload();
    // Read when the panel opens, on Refresh and every 15 seconds (2.7); every action below reads again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.token, refreshKey]);

  function failed(problem: unknown, otherwise: string) {
    if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
    else if (problem instanceof ApiError && problem.status === 422)
      setNote({
        text: "Mail to that address was refused less than a day ago, so it isn't sent again yet. Check the spelling, then try the corrected address.",
        tone: "bad",
      });
    else if (problem instanceof ApiError && problem.status === 429)
      setNote({
        text: "That's 50 invitations today, the daily limit. Try again tomorrow.",
        tone: "bad",
      });
    else if (problem instanceof NetworkError)
      setNote({
        text: "The server isn't responding. Try again in a moment.",
        tone: "bad",
      });
    else setNote({ text: otherwise, tone: "bad" });
  }

  async function inviteByEmail() {
    const address = email.trim();
    if (!looksLikeEmail(address)) {
      setNote({
        text: "Enter an email address like name@example.com.",
        tone: "bad",
      });
      return;
    }
    setBusy(true);
    setNote(null);
    try {
      await api.inviteByEmail(session.token, address);
      setEmail("");
      setNote({
        text: `Invitation sent to ${address.toLowerCase()}.`,
        tone: "good",
      });
      await reload();
    } catch (problem) {
      failed(problem, "That address can't be invited: it already belongs to a member, or it isn't a valid address.");
    } finally {
      setBusy(false);
    }
  }

  async function makeLink() {
    setBusy(true);
    setNote(null);
    setCopied(false);
    try {
      const made = await api.createInvitationLink(session.token);
      setLink(made.link);
      await reload();
    } catch (problem) {
      failed(problem, "That didn't work. Try again in a moment.");
    } finally {
      setBusy(false);
    }
  }

  async function copyLink() {
    if (!link) return;
    try {
      await navigator.clipboard.writeText(link);
      setCopied(true);
    } catch {
      setCopied(false); // the browser refused; the link is selectable on the page
    }
  }

  async function withdraw(invitation: InvitationView) {
    try {
      await api.withdrawInvitation(session.token, invitation.invitation_id);
      setNote({ text: `Withdrawn. That link no longer works.`, tone: "good" });
      await reload();
    } catch (problem) {
      failed(problem, "That invitation can't be withdrawn any more.");
    }
  }

  return (
    <section className="invite-panel" aria-label="Invite people">
      <div className="card">
        <h2 className="card-title">Invite by email</h2>
        <p className="field-note">Brief! mails them a link that works once, for {lifetime}. Opening it confirms their address.</p>
        <form
          className="banner-form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void inviteByEmail();
          }}
        >
          <label className="visually-hidden" htmlFor="invite-email">
            Their email address
          </label>
          <input
            id="invite-email"
            type="email"
            inputMode="email"
            autoComplete="off"
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
            {busy ? "Sending…" : "Send invitation"}
          </button>
        </form>
        {note && (
          <p className="field-note" data-tone={note.tone} role="status">
            {note.text}
          </p>
        )}
      </div>

      <div className="card">
        <h2 className="card-title">Or share a link yourself</h2>
        <p className="field-note">For a text message or a chat. Anyone who has the link can use it, once, within {lifetime}.</p>
        {link ? (
          <div className="link-box">
            <input
              id="invite-link"
              type="text"
              value={link}
              readOnly
              onFocus={(event) => event.target.select()}
              aria-label="Invitation link"
            />
            <button type="button" className="btn btn-primary" onClick={() => void copyLink()}>
              {copied ? "Copied" : "Copy"}
            </button>
          </div>
        ) : (
          <button type="button" className="btn btn-quiet" disabled={busy} onClick={() => void makeLink()}>
            Create a link
          </button>
        )}
        {link && (
          <p className="field-note">
            This link is shown only now.{" "}
            {/* 2.7.1: a second link no longer needs the page opened again. Each press makes a new, separate invitation. */}
            <button type="button" className="link link-inline" disabled={busy} onClick={() => void makeLink()}>
              {busy ? "Making…" : "Make another link"}
            </button>
          </p>
        )}
      </div>

      <div className="card">
        <h2 className="card-title">Sent</h2>
        {sent === null ? (
          <p className="field-note">Loading…</p>
        ) : sent.length === 0 ? (
          <p className="field-note">No invitations yet.</p>
        ) : (
          <ul className="invitations">
            {sent.map((invitation) => (
              <li key={invitation.invitation_id} data-status={invitation.status}>
                <span className="invitation-who">{invitation.email ?? "Link to share"}</span>
                <span className="invitation-status">
                  {invitation.status === "used" && invitation.joined_as
                    ? `Joined as ${invitation.joined_as}`
                    : `${STATUS_TEXT[invitation.status]} · sent ${relativeTime(invitation.created_at)}`}
                </span>
                {WITHDRAWABLE.includes(invitation.status) && (
                  <button type="button" className="link" onClick={() => void withdraw(invitation)}>
                    Withdraw
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
