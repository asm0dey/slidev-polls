import { describe, it, expect } from "vitest";
import { formatRelative } from "./formatRelative";

const now = new Date("2026-05-12T22:30:00Z");

describe("formatRelative", () => {
  it("uses 'today HH:MM' for same-day", () => {
    const earlier = new Date("2026-05-12T08:05:00Z").toISOString();
    expect(formatRelative(earlier, now)).toMatch(/^today \d{2}:\d{2}$/);
  });
  it("uses 'yesterday HH:MM' for the previous day", () => {
    const yesterday = new Date("2026-05-11T22:00:00Z").toISOString();
    expect(formatRelative(yesterday, now)).toMatch(/^yesterday \d{2}:\d{2}$/);
  });
  it("uses 'Mon D' for the current year", () => {
    const mar4 = new Date("2026-03-04T12:00:00Z").toISOString();
    expect(formatRelative(mar4, now)).toMatch(/^[A-Z][a-z]{2} \d{1,2}$/);
  });
  it("uses 'YYYY' for previous calendar years", () => {
    const old = new Date("2024-12-01T00:00:00Z").toISOString();
    expect(formatRelative(old, now)).toBe("2024");
  });
});
