import { useEffect, useState } from "react";
import { ApiError, NetworkError, api } from "../api";
import { type PushState, isAppleMobile, pushState, turnOffPush, turnOnPush } from "../lib/push";
import type { Session } from "../types";

interface Props {
  session: Session;
  /** "card": the full card in Your account. "prompt": one line above the mentions, shown only while off. */
  variant: "card" | "prompt";
  /** The state changed (the app stops its own in-page alerts while notifications are on). */
  onChange?: (state: PushState) => void;
  /** The session ended (401) while the page was open. */
  onSessionEnded: () => void;
}

/**
 * Notifications for mentions on this device (2.8). See lib/push.ts for what happens underneath.
 * Shown only when the server has notifications on (ServerInfo.push); the parent decides that.
 */
export function NotificationsCard({ session, variant, onChange, onSessionEnded }: Props) {
  const [state, setState] = useState<PushState | null>(null); // null while it is being found out
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ text: string; tone: "good" | "bad" | "info" } | null>(null);

  useEffect(() => {
    let current = true;
    void pushState(session.token).then((found) => {
      if (!current) return;
      setState(found);
      onChange?.(found);
    });
    return () => {
      current = false;
    };
    // Asked once per session; onChange is the parent's and may be a new function on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session.token]);

  function changed(next: PushState) {
    setState(next);
    onChange?.(next);
  }

  function failed(problem: unknown) {
    if (problem instanceof ApiError && problem.status === 401) onSessionEnded();
    else if (problem instanceof ApiError && problem.status === 409)
      setNote({
        text: "Notifications need “Stay signed in on this device”. Sign out, then sign in again with that box ticked.",
        tone: "bad",
      });
    else if (problem instanceof ApiError && problem.status === 429)
      setNote({ text: "A test was sent less than a minute ago. Wait a moment, then try again.", tone: "bad" });
    else if (problem instanceof NetworkError) setNote({ text: "The server isn't responding. Try again in a moment.", tone: "bad" });
    else setNote({ text: "That didn't work. Try again in a moment.", tone: "bad" });
  }

  async function turnOn() {
    setBusy(true);
    setNote(null);
    try {
      const next = await turnOnPush(session.token);
      changed(next);
      if (next === "on") setNote({ text: "Notifications are on for this device.", tone: "good" });
      else if (next === "off") setNote({ text: "Nothing changed: the browser's question was closed without an answer.", tone: "info" });
    } catch (problem) {
      failed(problem);
    } finally {
      setBusy(false);
    }
  }

  async function turnOff() {
    setBusy(true);
    setNote(null);
    try {
      await turnOffPush(session.token);
      changed("off");
      setNote({ text: "Notifications are off for this device.", tone: "info" });
    } catch (problem) {
      failed(problem);
    } finally {
      setBusy(false);
    }
  }

  async function sendTest() {
    setBusy(true);
    setNote(null);
    try {
      const sent = await api.pushTest(session.token);
      setNote(
        sent > 0
          ? { text: "Sent. It should appear in a few seconds.", tone: "good" }
          : { text: "This device isn't registered any more. Turn notifications off and on again.", tone: "bad" },
      );
    } catch (problem) {
      failed(problem);
    } finally {
      setBusy(false);
    }
  }

  if (state === null) return null;

  if (variant === "prompt") {
    // Above the mentions: only an offer while it makes sense. "On" and "blocked" are dealt with in Your account.
    if (state === "install-first")
      return (
        <p className="alerts">
          On iPhone and iPad, notifications work in the installed app: tap Share, then Add to Home Screen, and open Brief! from there.
        </p>
      );
    if (state === "needs-stay")
      return (
        <p className="alerts">
          To get notifications on this device, sign out and sign in again with “Stay signed in on this device” ticked.
        </p>
      );
    if (state !== "off") return null;
    return (
      <p className="alerts">
        Get a notification when someone mentions you, even when Brief! is closed.{" "}
        <button type="button" className="link" disabled={busy} onClick={() => void turnOn()}>
          {busy ? "Turning on…" : "Turn on notifications"}
        </button>
        {note && (
          <span className="field-note" data-tone={note.tone} role="status">
            {" "}
            {note.text}
          </span>
        )}
      </p>
    );
  }

  return (
    <div className="card">
      <h2 className="card-title">Notifications</h2>
      {state === "on" ? (
        <p>On for this device. When someone mentions you, you get a notification, even when Brief! is closed.</p>
      ) : state === "off" ? (
        <p>Get a notification on this device when someone mentions you, even when Brief! is closed.</p>
      ) : state === "blocked" ? (
        <p>Notifications are blocked for this site. To allow them, open the site settings of your browser (the icon left of the address).</p>
      ) : state === "needs-stay" ? (
        <p>
          Notifications need “Stay signed in on this device”: this sign-in ends when the tab closes, so a tap on a notification
          would find you signed out. To turn them on here, sign out and sign in again with that box ticked.
        </p>
      ) : state === "install-first" ? (
        <p>
          On {isAppleMobile() ? "iPhone and iPad" : "this device"}, notifications work in the installed app: tap Share, then Add to Home
          Screen, and open Brief! from there.
        </p>
      ) : (
        <p>This browser can't show notifications. Try a recent Chrome, Edge, Firefox or Safari.</p>
      )}
      {state === "on" && (
        <p className="field-note">
          The notification shows who mentioned you and the start of the post, also on a locked screen. It travels encrypted: the push
          service of your browser carries it but can't read it.
        </p>
      )}
      {(state === "on" || state === "off") && (
        <p className="banner-actions">
          {state === "off" ? (
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void turnOn()}>
              {busy ? "Turning on…" : "Turn on notifications"}
            </button>
          ) : (
            <>
              <button type="button" className="btn btn-quiet" disabled={busy} onClick={() => void sendTest()}>
                Send a test notification
              </button>
              <button type="button" className="link" disabled={busy} onClick={() => void turnOff()}>
                Turn off on this device
              </button>
            </>
          )}
        </p>
      )}
      {note && (
        <p className="field-note" data-tone={note.tone} role="status">
          {note.text}
        </p>
      )}
    </div>
  );
}
