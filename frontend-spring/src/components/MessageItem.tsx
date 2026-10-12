import { useEffect, useRef, useState } from "react";
import type { CSSProperties } from "react";
import type { Message, PublicAccount } from "../types";
import { MAX_MESSAGE_LENGTH } from "../types";
import { absoluteTime, relativeTime } from "../lib/time";
import { PostText } from "./PostText";
import { Ruler } from "./Ruler";

interface Props {
  message: Message;
  authorName: string;
  /** All members, so names written in the text can be recognised as mentions. */
  accounts: PublicAccount[];
  isOwn: boolean;
  isNew: boolean;
  /** True if this post names the signed-in member. */
  mentionsYou: boolean;
  onOpenAuthor: (accountId: number) => void;
  onSave: (messageId: number, text: string) => Promise<boolean>;
  onDelete: (messageId: number) => Promise<void>;
}

type Mode = "view" | "edit" | "confirm-delete";

export function MessageItem({ message, authorName, accounts, isOwn, isNew, mentionsYou, onOpenAuthor, onSave, onDelete }: Props) {
  const [mode, setMode] = useState<Mode>("view");
  const [draft, setDraft] = useState(message.message_text);
  const [busy, setBusy] = useState(false);
  const editRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (mode === "edit") {
      const el = editRef.current;
      el?.focus();
      el?.setSelectionRange(el.value.length, el.value.length);
    }
  }, [mode]);

  const remaining = MAX_MESSAGE_LENGTH - draft.length;
  const canSave = !busy && draft.trim().length > 0 && remaining >= 0 && draft !== message.message_text;
  const used = Math.round((message.message_text.length / MAX_MESSAGE_LENGTH) * 100);

  async function save() {
    if (!canSave) return;
    setBusy(true);
    const ok = await onSave(message.message_id, draft);
    setBusy(false);
    if (ok) setMode("view");
  }

  async function remove() {
    setBusy(true);
    await onDelete(message.message_id);
    setBusy(false);
  }

  return (
    <li className="post" data-new={isNew} data-mention={mentionsYou}>
      <span className="avatar" style={{ "--hue": (message.posted_by * 47) % 360 } as CSSProperties} aria-hidden="true">
        {authorName.charAt(0).toUpperCase()}
      </span>

      <div className="post-body">
        <div className="post-head">
          <button type="button" className="author" onClick={() => onOpenAuthor(message.posted_by)}>
            {authorName}
          </button>
          <time dateTime={new Date(message.time_posted_epoch * 1000).toISOString()} title={absoluteTime(message.time_posted_epoch)}>
            {relativeTime(message.time_posted_epoch)}
          </time>
        </div>

        {mode === "edit" ? (
          <form
            className="post-edit"
            onSubmit={(event) => {
              event.preventDefault();
              void save();
            }}
          >
            <label className="visually-hidden" htmlFor={`edit-${message.message_id}`}>
              Edit your post
            </label>
            <textarea
              id={`edit-${message.message_id}`}
              ref={editRef}
              className="composer-text"
              rows={3}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Escape") {
                  setDraft(message.message_text);
                  setMode("view");
                }
              }}
            />
            <Ruler length={draft.length} />
            <div className="composer-foot">
              <span className="count" data-over={remaining < 0}>
                {remaining >= 0 ? `${remaining} left` : `${-remaining} over the limit`}
              </span>
              <button
                type="button"
                className="btn btn-quiet"
                onClick={() => {
                  setDraft(message.message_text);
                  setMode("view");
                }}
              >
                Cancel
              </button>
              <button type="submit" className="btn btn-primary" disabled={!canSave}>
                {busy ? "Saving…" : "Save"}
              </button>
            </div>
          </form>
        ) : (
          <p className="post-text">
            <PostText text={message.message_text} accounts={accounts} onOpenAuthor={onOpenAuthor} />
          </p>
        )}

        {mode !== "edit" && (
          <div className="post-foot">
            <span className="used" title={`${message.message_text.length} of ${MAX_MESSAGE_LENGTH} characters`}>
              <span className="used-bar">
                <span style={{ width: `${used}%` }} />
              </span>
              {message.message_text.length}
            </span>

            {isOwn && mode === "view" && (
              <span className="post-actions">
                <button type="button" className="link" onClick={() => setMode("edit")}>
                  Edit
                </button>
                <button type="button" className="link" onClick={() => setMode("confirm-delete")}>
                  Delete
                </button>
              </span>
            )}

            {isOwn && mode === "confirm-delete" && (
              <span className="post-actions" role="group" aria-label="Confirm delete">
                <span className="confirm-text">Delete this post?</span>
                <button type="button" className="link link-danger" disabled={busy} onClick={() => void remove()}>
                  {busy ? "Deleting…" : "Delete"}
                </button>
                <button type="button" className="link" disabled={busy} onClick={() => setMode("view")}>
                  Keep
                </button>
              </span>
            )}
          </div>
        )}
      </div>
    </li>
  );
}
