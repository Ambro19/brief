import { useState } from "react";
import { MAX_MESSAGE_LENGTH } from "../types";
import { Ruler } from "./Ruler";

interface Props {
  username: string;
  /** Resolves true when the post was saved, so the box can be cleared. */
  onPost: (text: string) => Promise<boolean>;
  /** Why this member cannot post right now (an unconfirmed email address), or null if they can. */
  blocked?: string | null;
}

export function Composer({ username, onPost, blocked = null }: Props) {
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);

  const remaining = MAX_MESSAGE_LENGTH - text.length;
  const canPost = !busy && blocked === null && text.trim().length > 0 && remaining >= 0;

  async function submit() {
    if (!canPost) return;
    setBusy(true);
    const saved = await onPost(text);
    setBusy(false);
    if (saved) setText("");
  }

  return (
    <form
      className="composer"
      onSubmit={(event) => {
        event.preventDefault();
        void submit();
      }}
    >
      <label className="visually-hidden" htmlFor="composer-text">
        Write a post
      </label>
      <textarea
        id="composer-text"
        className="composer-text"
        rows={3}
        placeholder={blocked ?? `What's on your mind, ${username}?`}
        disabled={blocked !== null}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            void submit();
          }
        }}
      />
      <Ruler length={text.length} />
      <div className="composer-foot">
        <span className="count" data-over={remaining < 0}>
          {remaining >= 0 ? `${remaining} left` : `${-remaining} over the limit`}
        </span>
        <span className="hint">Ctrl + Enter to post</span>
        <button type="submit" className="btn btn-primary" disabled={!canPost}>
          {busy ? "Posting…" : "Post"}
        </button>
      </div>
    </form>
  );
}
