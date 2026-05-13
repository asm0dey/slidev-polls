import { describe, it, expect } from "vitest";
import { formatRelative } from "./formatRelative";

// Construct fixtures with local-time constructors so the test does not depend
// on the runner's TZ — `formatRelative` does its day arithmetic in local time,
// and a UTC ISO string can land on a different local day depending on the host.
const now = new Date(2026, 4, 12, 22, 30); // May 12 2026, 22:30 local

describe("formatRelative", () => {
  it("uses 'today HH:MM' for same-day", () => {
    const earlier = new Date(2026, 4, 12, 8, 5).toISOString();
    expect(formatRelative(earlier, now)).toMatch(/^today \d{2}:\d{2}$/);
  });
  it("uses 'yesterday HH:MM' for the previous day", () => {
    const yesterday = new Date(2026, 4, 11, 22, 0).toISOString();
    expect(formatRelative(yesterday, now)).toMatch(/^yesterday \d{2}:\d{2}$/);
  });
  it("uses 'Mon D' for the current year", () => {
    const mar4 = new Date(2026, 2, 4, 12, 0).toISOString();
    expect(formatRelative(mar4, now)).toMatch(/^[A-Z][a-z]{2} \d{1,2}$/);
  });
  it("uses 'YYYY' for previous calendar years", () => {
    const old = new Date(2024, 11, 1, 0, 0).toISOString();
    expect(formatRelative(old, now)).toBe("2024");
  });
});
