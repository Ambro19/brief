import { useMemo } from "react";
import { tokenize } from "../lib/richText";
import type { PublicAccount } from "../types";

interface Props {
  text: string;
  accounts: PublicAccount[];
  onOpenAuthor: (accountId: number) => void;
}

/**
 * The text of a post, with web addresses as links and member names as buttons.
 * Only the address itself is clickable - not the words or quotes around it.
 */
export function PostText({ text, accounts, onOpenAuthor }: Props) {
  const tokens = useMemo(() => tokenize(text, accounts), [text, accounts]);

  return (
    <>
      {tokens.map((token, index) => {
        if (token.kind === "link") {
          // A new tab, and "noopener noreferrer" so the opened page cannot reach back into this one.
          // "nofollow" tells search engines these are members' links, not endorsements.
          return (
            <a key={index} className="post-link" href={token.href} target="_blank" rel="noopener noreferrer nofollow">
              {token.text}
            </a>
          );
        }
        if (token.kind === "mention") {
          return (
            <button key={index} type="button" className="mention" onClick={() => onOpenAuthor(token.accountId)}>
              {token.text}
            </button>
          );
        }
        return <span key={index}>{token.text}</span>;
      })}
    </>
  );
}
