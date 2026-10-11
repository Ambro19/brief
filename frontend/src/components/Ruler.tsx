import { MAX_MESSAGE_LENGTH } from "../types";

/**
 * The 255-character limit drawn as a ruler: a mark moves along it as you type,
 * turns amber for the last 25 characters and red once you are over.
 */
export function Ruler({ length }: { length: number }) {
  const max = MAX_MESSAGE_LENGTH;
  const ratio = Math.min(length / max, 1);
  const state = length > max ? "over" : length >= max - 25 ? "near" : "ok";

  return (
    <div className="ruler" data-state={state} role="img" aria-label={`${length} of ${max} characters used`}>
      <div className="ruler-strip">
        <div className="ruler-fill" style={{ width: `${ratio * 100}%` }} />
        <div className="ruler-ticks" />
        {[0, 1, 2, 3].map((i) => (
          <span key={i} className="ruler-major" style={{ left: `${(i / 3) * 100}%` }} />
        ))}
      </div>
      <div className="ruler-scale" aria-hidden="true">
        <span>0</span>
        <span>85</span>
        <span>170</span>
        <span>255</span>
      </div>
    </div>
  );
}
