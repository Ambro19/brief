import { useRef, useState } from "react";
import type { RefObject } from "react";
import { ApiError, NetworkError, api } from "../api";
import { looksLikeEmail } from "../lib/links";
import { loadStayChoice, saveStayChoice } from "../lib/session";
import type { PasswordPolicy, ServerInfo, Session } from "../types";

/**
 * The four forms of the account panel.
 *
 *   signin     username and password
 *   register   a new account; asks for an email address and an invitation code when the server wants them
 *   forgot     "Forgot password?": an email address, to which a link is mailed (2.4)
 *   reset      opened from that link: choose the new password (2.4)
 */
type Mode = "signin" | "register" | "forgot" | "reset";

const USERNAME_RULE = /^[A-Za-z0-9._-]{3,30}$/;
const USERNAME_HINT = "3 to 30 letters, numbers, dots, dashes or underscores.";

const PASSWORD_HINT: Record<PasswordPolicy, string> = {
  basic: "At least 4 characters.",
  strong: "At least 8 characters, with a letter and a number.",
};

/** What the forms assume until the server has said what it is like: the plainest server there is. */
const PLAIN_SERVER: ServerInfo = { passwordPolicy: "basic", email: "none", board: "open" };

/** Says what is wrong with a new password, or null if it meets the server's rule. Mirrors PasswordPolicy.java. */
function passwordProblem(password: string, username: string, policy: PasswordPolicy): string | null {
  if (policy === "basic") return password.length < 4 ? "Use a password of at least 4 characters." : null;
  if (password.length < 8) return "Use a password of at least 8 characters.";
  if (!/\p{L}/u.test(password) || !/\p{Nd}/u.test(password)) return "Use at least one letter and one number in the password.";
  if (username !== "" && password.toLowerCase() === username.toLowerCase()) return "Choose a password that is different from your username.";
  return null;
}

function waitText(seconds: number | null): string {
  if (seconds === null) return "a few minutes";
  const minutes = Math.ceil(seconds / 60);
  return minutes <= 1 ? "a minute" : `${minutes} minutes`;
}

/** What the line under a field says, and whether it reads as neutral, good or bad. */
type Note = { text: string; tone: "plain" | "good" | "bad" };

/** The fields that have such a line. */
type FieldName = "invite" | "username" | "email" | "password";

interface Props {
  /** `stay` is true if the member asked to stay signed in after the tab or the app is closed. */
  onSignedIn: (session: Session, isNewAccount: boolean, stay: boolean) => void;
  /** Usernames that already exist, in lower case, or null when the member list is not known (not loaded, or a closed board). */
  takenUsernames: ReadonlySet<string> | null;
  /** What the server said about itself, or null while it has not answered yet. */
  server: ServerInfo | null;
  /** The secret of a password link this page was opened with, or null. With a secret the panel opens on the "reset" form. */
  resetSecret: string | null;
  /** The password link was used, or put aside: the app forgets the secret (and, if it was used, any session in this browser). */
  onResetClosed: (passwordChanged: boolean) => void;
}

export function AuthPanel({ onSignedIn, takenUsernames, server, resetSecret, onResetClosed }: Props) {
  const [mode, setMode] = useState<Mode>(resetSecret ? "reset" : "signin");
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [inviteCode, setInviteCode] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPasswords, setShowPasswords] = useState(false);
  const [stay, setStay] = useState(() => loadStayChoice());
  /** A problem that belongs to no single field ("that username and password don't match"); shown above the button. */
  const [error, setError] = useState<string | null>(null);
  /**
   * A problem with one field; shown in the line under that field, where the cursor is put.
   * Since 2.4 the form can be five fields tall, and a message at its foot would be off the screen.
   */
  const [fieldProblem, setFieldProblem] = useState<{ field: FieldName; text: string } | null>(null);
  /** A calm message above the form: "Password changed", "a link is on its way". */
  const [notice, setNotice] = useState<string | null>(null);
  /** True after a form was sent with the two passwords differing (or the second one empty), until either is edited. */
  const [confirmRejected, setConfirmRejected] = useState(false);
  /** The address a "Forgot password?" request was sent for; while set, the form is replaced by the answer. */
  const [linkRequestedFor, setLinkRequestedFor] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const usernameInput = useRef<HTMLInputElement>(null);
  const emailInput = useRef<HTMLInputElement>(null);
  const inviteInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const confirmInput = useRef<HTMLInputElement>(null);

  const info = server ?? PLAIN_SERVER;
  const policy = info.passwordPolicy;
  const registering = mode === "register";
  const choosingPassword = registering || mode === "reset";
  const asksEmail = registering && info.email !== "none";
  const asksInvite = registering && info.board === "closed";
  const name = username.trim();
  const address = email.trim();
  const nameIsValid = USERNAME_RULE.test(name);
  // Compared in lower case: "Maya" is taken if "maya" exists (the server applies the same rule).
  const nameIsTaken = nameIsValid && takenUsernames !== null && takenUsernames.has(name.toLowerCase());

  const usernameNote: Note = nameIsTaken
    ? { text: `"${name}" is already taken.`, tone: "bad" }
    : nameIsValid && takenUsernames !== null
      ? { text: `"${name}" is available.`, tone: "good" }
      : { text: USERNAME_HINT, tone: "plain" };

  // One line under the second password field says where things stand. Since 2.4 a refused form
  // speaks through this same line, so a mismatch is reported once, not twice.
  const confirmNote: Note =
    confirm === ""
      ? confirmRejected
        ? { text: "Type the password a second time to confirm it.", tone: "bad" }
        : { text: "Type it once more, so a typing slip can't lock you out.", tone: "plain" }
      : confirm === password
        ? { text: "The two passwords match.", tone: "good" }
        : confirmRejected
          ? { text: "The two passwords are not the same. Type them again.", tone: "bad" }
          : { text: "The two passwords are not the same yet.", tone: "plain" };

  function switchMode(next: Mode) {
    if (mode === "reset" && next !== "reset") onResetClosed(false); // the link is put aside; it stays good until it expires
    if (mode === "reset") setPassword(""); // a new password that was typed but not set is not carried into "Sign in"
    setMode(next);
    setError(null);
    setFieldProblem(null);
    setNotice(null);
    setConfirm("");
    setConfirmRejected(false);
    setShowPasswords(false);
    setLinkRequestedFor(null);
  }

  /** Stores what was typed, and takes down an earlier error message: it described the old input. */
  function edited(store: (value: string) => void, value: string) {
    store(value);
    setError(null);
    setFieldProblem(null);
    setConfirmRejected(false);
  }

  const inputs: Record<FieldName, RefObject<HTMLInputElement>> = { invite: inviteInput, username: usernameInput, email: emailInput, password: passwordInput };

  /** Shows a message under the field it is about, and puts the cursor there. */
  function reject(text: string, field: FieldName) {
    setError(null);
    setFieldProblem({ field, text });
    inputs[field].current?.focus();
  }

  /** The line under a field: the problem with it if there is one, otherwise what it usually says. */
  function noteFor(field: FieldName, usual: Note): Note {
    return fieldProblem?.field === field ? { text: fieldProblem.text, tone: "bad" } : usual;
  }

  /** Checks the two password fields of a form that chooses a password. True if they may be sent. */
  function newPasswordIsAcceptable(forUsername: string): boolean {
    const weakness = passwordProblem(password, forUsername, policy);
    if (weakness) {
      reject(weakness, "password");
      return false;
    }
    if (confirm !== password) {
      // No message of its own: the line under the field turns into the message (see confirmNote).
      setError(null);
      setFieldProblem(null);
      setConfirmRejected(true);
      confirmInput.current?.focus();
      return false;
    }
    return true;
  }

  async function submitSignInOrRegister() {
    if (registering) {
      if (asksInvite && inviteCode.trim() === "") return reject("Enter the invitation code you were given.", "invite");
      if (!nameIsValid) return reject(`Choose a username of ${USERNAME_HINT.toLowerCase()}`, "username");
      if (nameIsTaken) return reject(`"${name}" is already taken. Try another username.`, "username");
      if (asksEmail && address === "" && info.email === "required") return reject("Enter your email address.", "email");
      if (asksEmail && address !== "" && !looksLikeEmail(address)) return reject("Enter an email address like name@example.com.", "email");
      if (!newPasswordIsAcceptable(name)) return;
    } else if (!name || !password) {
      setError("Enter your username and password.");
      return;
    }

    setBusy(true);
    setError(null);
    setFieldProblem(null);
    setNotice(null);
    let accountCreated = false;
    try {
      if (registering) {
        await api.register(name, password, asksEmail ? address : undefined, asksInvite ? inviteCode.trim() : undefined);
        accountCreated = true;
      }
      const session = await api.login(name, password);
      saveStayChoice(stay);
      onSignedIn(session, registering, stay);
    } catch (problem) {
      const status = problem instanceof ApiError ? problem.status : null;
      if (problem instanceof NetworkError) {
        setError("The server isn't responding. Check that the API is running, then try again.");
      } else if (registering && !accountCreated && status === 403) {
        reject("That invitation code isn't right. Check it with the person who invited you.", "invite");
      } else if (registering && !accountCreated && status === 429) {
        reject(`Too many tries with a wrong invitation code. Try again in ${waitText((problem as ApiError).retryAfterSeconds)}.`, "invite");
      } else if (registering && !accountCreated && status === 400) {
        // The server answers 400 without saying which rule was broken. What this form could not check itself:
        if (!asksEmail) reject(`"${name}" is already taken. Try another username.`, "username");
        else if (takenUsernames !== null) reject("That email address already has an account, or isn't a valid address. Use another one, or sign in.", "email");
        else setError("That username or email address is already in use. Try another, or sign in.");
      } else if (status === 401) {
        setError("That username and password don't match.");
      } else if (status === 429) {
        setError(`Too many attempts for this username. Try again in ${waitText((problem as ApiError).retryAfterSeconds)}.`);
      } else {
        setError("That didn't work. Try again in a moment.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function submitForgot() {
    if (!looksLikeEmail(address)) return reject("Enter the email address of your account.", "email");
    setBusy(true);
    setError(null);
    setFieldProblem(null);
    try {
      await api.forgotPassword(address);
      setLinkRequestedFor(address);
    } catch (problem) {
      if (problem instanceof NetworkError) setError("The server isn't responding. Try again in a moment.");
      else if (problem instanceof ApiError && problem.status === 429) setError(`Too many requests. Try again in ${waitText(problem.retryAfterSeconds)}.`);
      else setError("That didn't work. Check the address and try again.");
    } finally {
      setBusy(false);
    }
  }

  async function submitReset() {
    if (!resetSecret) return;
    if (!newPasswordIsAcceptable("")) return;
    setBusy(true);
    setError(null);
    setFieldProblem(null);
    try {
      await api.resetPassword(resetSecret, password);
      onResetClosed(true);
      setMode("signin");
      setPassword("");
      setConfirm("");
      setShowPasswords(false);
      setNotice("Password changed. Sign in with the new one.");
    } catch (problem) {
      if (problem instanceof NetworkError) {
        setError("The server isn't responding. Try again in a moment.");
      } else {
        setError("That didn't work. The link may have expired or been used already, or the password is the same as your username. Try another password, or ask for a new link.");
      }
    } finally {
      setBusy(false);
    }
  }

  function submit() {
    if (mode === "forgot") void submitForgot();
    else if (mode === "reset") void submitReset();
    else void submitSignInOrRegister();
  }

  const inviteNote = noteFor("invite", { text: "This board is by invitation. Use the code from the person who invited you.", tone: "plain" });
  const shownUsernameNote = noteFor("username", usernameNote);
  const emailNote = noteFor("email", {
    text:
      mode === "forgot"
        ? "The address you confirmed for your account. We'll mail it a link for choosing a new password."
        : "We mail you one link to confirm it. It is your way back in if you forget your password. Other members never see it.",
    tone: "plain",
  });
  const passwordNote = noteFor("password", { text: PASSWORD_HINT[policy], tone: "plain" });
  const passwordType = choosingPassword && showPasswords ? "text" : "password";
  const waitingForServer = registering && server === null;

  return (
    <section className="auth" aria-label="Account">
      {mode === "signin" || mode === "register" ? (
        <div className="segmented" role="tablist" aria-label="Sign in or create an account">
          <button type="button" role="tab" aria-selected={mode === "signin"} onClick={() => switchMode("signin")}>
            Sign in
          </button>
          <button type="button" role="tab" aria-selected={mode === "register"} onClick={() => switchMode("register")}>
            Create account
          </button>
        </div>
      ) : (
        <h2 className="auth-title">{mode === "forgot" ? "Forgot your password?" : "Choose a new password"}</h2>
      )}

      {notice && (
        <p className="form-notice" role="status">
          {notice}
        </p>
      )}

      {mode === "forgot" && linkRequestedFor !== null ? (
        <div className="auth-form">
          <p className="form-notice" role="status">
            If <strong>{linkRequestedFor}</strong> is the confirmed address of an account, a link is on its way. It works once, for one hour.
          </p>
          <p className="field-note">Nothing after a few minutes? Look in the spam folder, and check the spelling of the address.</p>
          <button type="button" className="btn btn-primary btn-block" onClick={() => switchMode("signin")}>
            Back to sign in
          </button>
        </div>
      ) : (
        <form
          className="auth-form"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          {asksInvite && (
            <>
              <label htmlFor="auth-invite">Invitation code</label>
              <input
                id="auth-invite"
                ref={inviteInput}
                type="text"
                autoComplete="off"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={200}
                aria-describedby="auth-invite-note"
                value={inviteCode}
                onChange={(event) => edited(setInviteCode, event.target.value)}
              />
              <p className="field-note" id="auth-invite-note" data-tone={inviteNote.tone} aria-live="polite">
                {inviteNote.text}
              </p>
            </>
          )}

          {(mode === "signin" || registering) && (
            <>
              <label htmlFor="auth-username">Username</label>
              <input
                id="auth-username"
                ref={usernameInput}
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={registering ? 30 : undefined}
                aria-describedby={registering ? "auth-username-note" : undefined}
                value={username}
                onChange={(event) => edited(setUsername, event.target.value)}
              />
              {registering && (
                <p className="field-note" id="auth-username-note" data-tone={shownUsernameNote.tone} aria-live="polite">
                  {shownUsernameNote.text}
                </p>
              )}
            </>
          )}

          {(asksEmail || mode === "forgot") && (
            <>
              <label htmlFor="auth-email">{mode === "forgot" || info.email === "required" ? "Email address" : "Email address (optional)"}</label>
              <input
                id="auth-email"
                ref={emailInput}
                type="email"
                inputMode="email"
                autoComplete="email"
                autoCapitalize="none"
                spellCheck={false}
                maxLength={254}
                aria-describedby="auth-email-note"
                value={email}
                onChange={(event) => edited(setEmail, event.target.value)}
              />
              <p className="field-note" id="auth-email-note" data-tone={emailNote.tone} aria-live="polite">
                {emailNote.text}
              </p>
            </>
          )}

          {mode !== "forgot" && (
            <>
              <label htmlFor="auth-password">{mode === "reset" ? "New password" : "Password"}</label>
              <div className={choosingPassword ? "field field-with-toggle" : "field"}>
                <input
                  id="auth-password"
                  ref={passwordInput}
                  type={passwordType}
                  autoComplete={choosingPassword ? "new-password" : "current-password"}
                  aria-describedby={choosingPassword ? "auth-password-note" : undefined}
                  value={password}
                  onChange={(event) => edited(setPassword, event.target.value)}
                />
                {choosingPassword && (
                  <button
                    type="button"
                    className="field-toggle"
                    aria-pressed={showPasswords}
                    aria-label={showPasswords ? "Hide the passwords" : "Show the passwords"}
                    onClick={() => setShowPasswords((shown) => !shown)}
                  >
                    {showPasswords ? "Hide" : "Show"}
                  </button>
                )}
              </div>
              {choosingPassword && (
                <p className="field-note" id="auth-password-note" data-tone={passwordNote.tone} aria-live="polite">
                  {passwordNote.text}
                </p>
              )}
            </>
          )}

          {mode === "signin" && info.email !== "none" && (
            <p className="auth-forgot">
              <button type="button" className="link" onClick={() => switchMode("forgot")}>
                Forgot password?
              </button>
            </p>
          )}

          {choosingPassword && (
            <>
              <label htmlFor="auth-confirm">{mode === "reset" ? "Confirm new password" : "Confirm password"}</label>
              <input
                id="auth-confirm"
                ref={confirmInput}
                type={passwordType}
                autoComplete="new-password"
                aria-describedby="auth-confirm-note"
                aria-invalid={confirmNote.tone === "bad"}
                value={confirm}
                onChange={(event) => edited(setConfirm, event.target.value)}
              />
              <p className="field-note" id="auth-confirm-note" data-tone={confirmNote.tone} aria-live="polite">
                {confirmNote.text}
              </p>
            </>
          )}

          {(mode === "signin" || registering) && (
            <label className="check" htmlFor="auth-stay">
              <input id="auth-stay" type="checkbox" checked={stay} onChange={(event) => setStay(event.target.checked)} />
              <span>Stay signed in on this device</span>
            </label>
          )}

          {error && (
            <p className="form-error" role="alert">
              {error}
            </p>
          )}

          <button type="submit" className="btn btn-primary btn-block" disabled={busy || waitingForServer}>
            {busy || waitingForServer
              ? "One moment…"
              : registering
                ? "Create account"
                : mode === "forgot"
                  ? "Mail me a link"
                  : mode === "reset"
                    ? "Set new password"
                    : "Sign in"}
          </button>
          {registering && (
            <p className="field-note auth-after">
              {asksEmail && info.email === "required"
                ? "You'll be signed in at once. To post, open the link we mail you."
                : "You'll be signed in as soon as the account is created."}
            </p>
          )}
          {mode === "reset" && <p className="field-note auth-after">Setting a new password signs you out on every device.</p>}
          {(mode === "forgot" || mode === "reset") && (
            <p className="auth-after">
              <button type="button" className="link" onClick={() => switchMode("signin")}>
                Back to sign in
              </button>
            </p>
          )}
        </form>
      )}
    </section>
  );
}
