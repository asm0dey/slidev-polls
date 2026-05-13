const MS_PER_DAY = 86_400_000;

function startOfDay(d: Date): Date {
  const c = new Date(d);
  c.setHours(0, 0, 0, 0);
  return c;
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n);
}

export function formatRelative(iso: string, now: Date = new Date()): string {
  const then = new Date(iso);
  const sameYear = then.getFullYear() === now.getFullYear();
  const startThen = startOfDay(then).getTime();
  const startNow = startOfDay(now).getTime();
  const dayDelta = Math.round((startNow - startThen) / MS_PER_DAY);

  const hhmm = `${pad2(then.getHours())}:${pad2(then.getMinutes())}`;

  if (dayDelta === 0) return `today ${hhmm}`;
  if (dayDelta === 1) return `yesterday ${hhmm}`;
  if (sameYear) {
    return then.toLocaleDateString("en-US", { month: "short", day: "numeric" });
  }
  return String(then.getFullYear());
}
