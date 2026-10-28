import { useCallback, useEffect, useState } from "react";
import { ApiError, NetworkError, api } from "../api";
import { relativeTime } from "../lib/time";
import type { Session, SignedInSession } from "../types";

interface Props {
  session: Session;
  /** "Sign out everywhere else" gave this device a new token for the same session: keep using that one. */
  onTokenRenewed: (token: string) => void;
  /** The session ended (401) while the page was open. */
  onSessionEnded: () => void;
}

/** "Until 3 Nov" style: the day a long session ends if it is not used again. */
function day(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

/**
 * "Where you're signed in" (2.8): every device with a session, and the means to sign any of them
 * out. Shown only on a server that keeps sessions (ServerInfo.sessions).
 *
 * Signing a device out also stops its notifications. A lost phone can be signed out from here,
 * and a password changed through "Forgot password?" signs out every device at once.
 */
export function SessionsCard({ session, onTokenRenewed, onSessionEnded }: Props) {
  const [list, setList] = useState<SignedInSession[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ text: string; tone: "good" | "bad" } | null>(null);

  const failed = useCallback(
    (problem: unknown) => {
      if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
      else if (problem instanceof ApiError && problem.status === 409)
        setNote({ text: "Sign out and sign in again first: this sign-in is from before the list existed.", tone: "bad" });
      else if (problem instanceof NetworkError) setNote({ text: "The server isn't responding. Try again in a moment.", tone: "bad" });
      else setNote({ text: "That didn't work. Try again in a moment.", tone: "bad" });
    },
    [onSessionEnded],
  );

  const load = useCallback(
    async (token: string) => {
      try {
        setList(await api.sessions(token));
      } catch (problem) {
        failed(problem);
      }
    },
    [failed],
  );

  useEffect(() => {
    void load(session.token);
  }, [load, session.token]);

  async function signOutOne(target: SignedInSession) {
    setBusy(true);
    setNote(null);
    try {
      await api.endSession(session.token, target.session_id);
      setNote({ text: `${target.device} is signed out.`, tone: "good" });
    } catch (problem) {
      if (!(problem instanceof ApiError && problem.status === 404)) failed(problem); // 404: it had ended already
    } finally {
      await load(session.token);
      setBusy(false);
    }
  }

  async function signOutOthers() {
    setBusy(true);
    setNote(null);
    try {
      const result = await api.signOutOthers(session.token);
      onTokenRenewed(result.token);
      setNote({
        text: result.ended === 1 ? "1 other device is signed out." : `${result.ended} other devices are signed out.`,
        tone: "good",
      });
      await load(result.token);
    } catch (problem) {
      failed(problem);
    } finally {
      setBusy(false);
    }
  }

  const others = list?.filter((item) => !item.current).length ?? 0;

  return (
    <div className="card">
      <h2 className="card-title">Where you're signed in</h2>
      {list === null ? (
        <p className="field-note">{note ? note.text : "Loading…"}</p>
      ) : (
        <ul className="sessions">
          {list.map((item) => (
            <li key={item.session_id} data-current={item.current ? "true" : undefined}>
              <span className="session-device">
                {item.device}
                {item.current && <span className="session-here">This device</span>}
              </span>
              <span className="session-when">
                {item.current ? "Signed in " + relativeTime(item.created_at) : "Last used " + relativeTime(item.last_used_at)}
                {" · "}
                {item.stay ? `stays signed in until ${day(item.expires_at)}` : "for this visit"}
              </span>
              {!item.current && (
                <button type="button" className="link" disabled={busy} onClick={() => void signOutOne(item)}>
                  Sign out
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {others > 0 && (
        <p className="banner-actions">
          <button type="button" className="btn btn-quiet" disabled={busy} onClick={() => void signOutOthers()}>
            Sign out everywhere else
          </button>
        </p>
      )}
      <p className="field-note">
        "Stay signed in" lasts 30 days from the last use. Signing a device out also stops its notifications.
      </p>
      {note && list !== null && (
        <p className="field-note" data-tone={note.tone} role="status">
          {note.text}
        </p>
      )}
    </div>
  );
}
