import { useEffect, useRef, useState } from "react";
import type { RefObject } from "react";
import { ApiError, NetworkError, api } from "../api";
import { loadStayChoice, saveStayChoice } from "../lib/session";
import type { PasswordPolicy, Session } from "../types";

type Mode = "signin" | "register";

const USERNAME_RULE = /^[A-Za-z0-9._-]{3,30}$/;
const USERNAME_HINT = "3 to 30 letters, numbers, dots, dashes or underscores.";

const PASSWORD_HINT: Record<PasswordPolicy, string> = {
  basic: "At least 4 characters.",
  strong: "At least 8 characters, with a letter and a number.",
};

/** Says what is wrong with a new password, or null if it meets the server's rule. Mirrors PasswordPolicy.java. */
function passwordProblem(password: string, username: string, policy: PasswordPolicy): string | null {
  if (policy === "basic") return password.length < 4 ? "Use a password of at least 4 characters." : null;
  if (password.length < 8) return "Use a password of at least 8 characters.";
  if (!/\p{L}/u.test(password) || !/\p{Nd}/u.test(password)) return "Use at least one letter and one number in the password.";
  if (password.toLowerCase() === username.toLowerCase()) return "Choose a password that is different from your username.";
  return null;
}

function waitText(seconds: number | null): string {
  if (seconds === null) return "a few minutes";
  const minutes = Math.ceil(seconds / 60);
  return minutes <= 1 ? "a minute" : `${minutes} minutes`;
}

/** What the line under a field says, and whether it reads as neutral, good or bad. */
type Note = { text: string; tone: "plain" | "good" | "bad" };

interface Props {
  /** `stay` is true if the member asked to stay signed in after the tab or the app is closed. */
  onSignedIn: (session: Session, isNewAccount: boolean, stay: boolean) => void;
  /** Usernames that already exist, in lower case, or null while the member list has not been loaded. */
  takenUsernames: ReadonlySet<string> | null;
}

export function AuthPanel({ onSignedIn, takenUsernames }: Props) {
  const [mode, setMode] = useState<Mode>("signin");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPasswords, setShowPasswords] = useState(false);
  const [stay, setStay] = useState(() => loadStayChoice());
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [policy, setPolicy] = useState<PasswordPolicy>("basic");
  const usernameInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const confirmInput = useRef<HTMLInputElement>(null);

  // Ask the server once which password rule it applies, so the hint and the check below match it.
  useEffect(() => {
    let stillHere = true;
    void api.passwordPolicy().then((answer) => {
      if (stillHere) setPolicy(answer);
    });
    return () => {
      stillHere = false;
    };
  }, []);

  const registering = mode === "register";
  const name = username.trim();
  const nameIsValid = USERNAME_RULE.test(name);
  // Compared in lower case: "Maya" is taken if "maya" exists (the server applies the same rule).
  const nameIsTaken = nameIsValid && takenUsernames !== null && takenUsernames.has(name.toLowerCase());

  const usernameNote: Note = nameIsTaken
    ? { text: `"${name}" is already taken.`, tone: "bad" }
    : nameIsValid && takenUsernames !== null
      ? { text: `"${name}" is available.`, tone: "good" }
      : { text: USERNAME_HINT, tone: "plain" };

  const confirmNote: Note | null =
    confirm === ""
      ? null
      : confirm === password
        ? { text: "The two passwords match.", tone: "good" }
        : { text: "The two passwords are not the same yet.", tone: "plain" };

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setConfirm("");
    setShowPasswords(false);
  }

  /** Stores what was typed, and takes down an earlier error message: it described the old input. */
  function edited(store: (value: string) => void, value: string) {
    store(value);
    setError(null);
  }

  /** Shows a message and puts the cursor in the field it is about. */
  function reject(message: string, field: RefObject<HTMLInputElement>) {
    setError(message);
    field.current?.focus();
  }

  async function submit() {
    if (registering) {
      if (!nameIsValid) return reject(`Choose a username of ${USERNAME_HINT.toLowerCase()}`, usernameInput);
      if (nameIsTaken) return reject(`"${name}" is already taken. Try another username.`, usernameInput);
      const weakness = passwordProblem(password, name, policy);
      if (weakness) return reject(weakness, passwordInput);
      if (confirm === "") return reject("Type the password a second time to confirm it.", confirmInput);
      if (confirm !== password) return reject("The two passwords are not the same. Type them again.", confirmInput);
    } else if (!name || !password) {
      setError("Enter your username and password.");
      return;
    }

    setBusy(true);
    setError(null);
    try {
      if (registering) await api.register(name, password);
      const session = await api.login(name, password);
      saveStayChoice(stay);
      onSignedIn(session, registering, stay);
    } catch (problem) {
      if (problem instanceof NetworkError) {
        setError("The server isn't responding. Check that the API is running, then try again.");
      } else if (problem instanceof ApiError && problem.status === 401) {
        setError("That username and password don't match.");
      } else if (problem instanceof ApiError && problem.status === 429) {
        setError(`Too many attempts for this username. Try again in ${waitText(problem.retryAfterSeconds)}.`);
      } else if (problem instanceof ApiError && problem.status === 400 && registering) {
        setError(`"${name}" is already taken. Try another username.`);
      } else {
        setError("That didn't work. Try again in a moment.");
      }
    } finally {
      setBusy(false);
    }
  }

  const passwordType = registering && showPasswords ? "text" : "password";

  return (
    <section className="auth" aria-label="Account">
      <div className="segmented" role="tablist" aria-label="Sign in or create an account">
        <button type="button" role="tab" aria-selected={mode === "signin"} onClick={() => switchMode("signin")}>
          Sign in
        </button>
        <button type="button" role="tab" aria-selected={mode === "register"} onClick={() => switchMode("register")}>
          Create account
        </button>
      </div>

      <form
        className="auth-form"
        noValidate
        onSubmit={(event) => {
          event.preventDefault();
          void submit();
        }}
      >
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
          <p className="field-note" id="auth-username-note" data-tone={usernameNote.tone} aria-live="polite">
            {usernameNote.text}
          </p>
        )}

        <label htmlFor="auth-password">Password</label>
        <div className={registering ? "field field-with-toggle" : "field"}>
          <input
            id="auth-password"
            ref={passwordInput}
            type={passwordType}
            autoComplete={registering ? "new-password" : "current-password"}
            aria-describedby={registering ? "auth-password-note" : undefined}
            value={password}
            onChange={(event) => edited(setPassword, event.target.value)}
          />
          {registering && (
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
        {registering && (
          <p className="field-note" id="auth-password-note">
            {PASSWORD_HINT[policy]}
          </p>
        )}

        {registering && (
          <>
            <label htmlFor="auth-confirm">Confirm password</label>
            <input
              id="auth-confirm"
              ref={confirmInput}
              type={passwordType}
              autoComplete="new-password"
              aria-describedby="auth-confirm-note"
              value={confirm}
              onChange={(event) => edited(setConfirm, event.target.value)}
            />
            <p className="field-note" id="auth-confirm-note" data-tone={confirmNote?.tone ?? "plain"} aria-live="polite">
              {confirmNote?.text ?? "Type it once more, so a typing slip can't lock you out."}
            </p>
          </>
        )}

        <label className="check" htmlFor="auth-stay">
          <input id="auth-stay" type="checkbox" checked={stay} onChange={(event) => setStay(event.target.checked)} />
          <span>Stay signed in on this device</span>
        </label>

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}

        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? "One moment…" : registering ? "Create account" : "Sign in"}
        </button>
        {registering && <p className="field-note auth-after">You'll be signed in as soon as the account is created.</p>}
      </form>
    </section>
  );
}
