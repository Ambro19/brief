import { useState } from "react";
import { ApiError, NetworkError, api } from "../api";
import type { PushState } from "../lib/push";
import type { Profile, ServerInfo, Session } from "../types";
import { NotificationsCard } from "./NotificationsCard";
import { SessionsCard } from "./SessionsCard";

interface Props {
  session: Session;
  /** The member's own account, or null on a server without email (older servers). */
  profile: Profile | null;
  /** The account was deleted: the app signs out and says so. */
  onDeleted: () => void;
  /** The session ended (401) while the page was open: the app signs out and asks for a new sign-in. */
  onSessionEnded: () => void;
  /** What the server can do (2.8: sessions and notifications); null while it has not said. */
  server: ServerInfo | null;
  /** "Sign out everywhere else" issued this device a new token (2.8). */
  onTokenRenewed: (token: string) => void;
  /** Notifications on this device were turned on or off (2.8). */
  onPushChange: (state: PushState) => void;
}

/**
 * "Your account" (2.5): what Brief! holds about the member, and the way to delete it. Since 2.8
 * also notifications on this device, and where the member is signed in.
 *
 * Deleting asks for the password again, and for a tick that says what will happen, because it
 * cannot be undone: the account and every post it wrote are gone.
 */
export function AccountPanel({ session, profile, onDeleted, onSessionEnded, server, onTokenRenewed, onPushChange }: Props) {
  const [password, setPassword] = useState("");
  const [understood, setUnderstood] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isOwner = profile?.is_owner === true;

  async function deleteAccount() {
    if (!understood) return setError("Tick the box first, to confirm that you mean it.");
    if (password === "") return setError("Enter your password.");
    setBusy(true);
    setError(null);
    try {
      await api.deleteAccount(session, password);
      onDeleted();
    } catch (problem) {
      if (problem instanceof NetworkError) setError("The server isn't responding. Nothing was deleted.");
      // A session that ended while the page was open signs out (review of 2.5.0; it said "That didn't work").
      else if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
      else if (problem instanceof ApiError && problem.status === 403) setError("That password isn't right. Nothing was deleted.");
      else if (problem instanceof ApiError && problem.status === 429)
        setError("Too many wrong passwords. Wait a few minutes, then try again.");
      else if (problem instanceof ApiError && problem.status === 400) setError("You run this board, so your account can't be deleted.");
      else if (problem instanceof ApiError && problem.status === 404) setError("This server can't delete accounts yet.");
      else setError("That didn't work. Nothing was deleted.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="account-panel" aria-label="Your account">
      {server?.push && <NotificationsCard session={session} variant="card" onChange={onPushChange} onSessionEnded={onSessionEnded} />}
      {server?.sessions && <SessionsCard session={session} onTokenRenewed={onTokenRenewed} onSessionEnded={onSessionEnded} />}

      <div className="card">
        <h2 className="card-title">What Brief! keeps about you</h2>
        <dl className="facts">
          <dt>Username</dt>
          <dd>{session.username}, shown to every member</dd>
          {profile && (
            <>
              <dt>Email address</dt>
              <dd>
                {profile.email ?? "none yet"}
                {profile.email && (profile.email_verified ? ", confirmed" : ", not confirmed yet")}. Only you see it.
              </dd>
            </>
          )}
          <dt>Password</dt>
          <dd>Stored as a salted hash; nobody can read it, not even the person who runs Brief!.</dd>
          <dt>Posts</dt>
          <dd>Everything you posted, until you delete it or the account.</dd>
          {server?.sessions && (
            <>
              <dt>Sign-ins</dt>
              <dd>One per device: a name such as "Chrome on Windows", and when it was last used. Gone when you sign out.</dd>
            </>
          )}
          {server?.push && (
            <>
              <dt>Notifications</dt>
              <dd>For each device where you turned them on, the address of your browser's push service. Gone when you turn them off.</dd>
            </>
          )}
        </dl>
        <p className="field-note">
          More in the{" "}
          <a href="/privacy.html" target="_blank" rel="noopener">
            Privacy notice
          </a>
          .
        </p>
      </div>

      <div className="card card-danger">
        <h2 className="card-title">Delete your account</h2>
        {isOwner ? (
          <p className="field-note">
            You run this board, so your account can't be deleted from here: the board would have nobody left who can invite. Hand the board
            over first (the setting BRIEF_OWNER_USERNAME).
          </p>
        ) : (
          <form
            className="auth-form"
            noValidate
            onSubmit={(event) => {
              event.preventDefault();
              void deleteAccount();
            }}
          >
            <p>
              This deletes your account and every post you wrote, at once and for good. Your username and email address become free for
              anyone. It can't be undone.
            </p>
            <label className="check" htmlFor="delete-understood">
              <input
                id="delete-understood"
                type="checkbox"
                checked={understood}
                onChange={(event) => {
                  setUnderstood(event.target.checked);
                  setError(null);
                }}
              />
              <span>I understand: my account and my posts will be deleted.</span>
            </label>
            <label htmlFor="delete-password">Your password</label>
            <input
              id="delete-password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
                setError(null);
              }}
            />
            {error && (
              <p className="form-error" role="alert">
                {error}
              </p>
            )}
            <button type="submit" className="btn btn-danger btn-block" disabled={busy}>
              {busy ? "Deleting…" : "Delete my account"}
            </button>
          </form>
        )}
      </div>
    </section>
  );
}
