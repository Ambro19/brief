const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** "just now", "5 min ago", "3 h ago", "2 days ago", then a date. Input is epoch seconds. */
export function relativeTime(epochSeconds: number, nowMs: number = Date.now()): string {
  const diff = Math.floor(nowMs / 1000) - epochSeconds;
  if (diff < 45) return "just now";
  if (diff < HOUR) return `${Math.max(1, Math.round(diff / MINUTE))} min ago`;
  if (diff < DAY) return `${Math.round(diff / HOUR)} h ago`;
  if (diff < 7 * DAY) {
    const days = Math.round(diff / DAY);
    return days === 1 ? "yesterday" : `${days} days ago`;
  }
  return new Date(epochSeconds * 1000).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

export function absoluteTime(epochSeconds: number): string {
  return new Date(epochSeconds * 1000).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}
