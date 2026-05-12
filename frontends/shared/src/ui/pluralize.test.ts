import { describe, it, expect } from "vitest";
import { pluralize } from "./pluralize";

describe("pluralize", () => {
  it("returns singular for 1", () => {
    expect(pluralize(1, "poll")).toBe("1 poll");
  });
  it("returns plural for 0", () => {
    expect(pluralize(0, "poll")).toBe("0 polls");
  });
  it("returns plural for 2", () => {
    expect(pluralize(2, "poll")).toBe("2 polls");
  });
  it("accepts explicit plural form", () => {
    expect(pluralize(3, "person", "people")).toBe("3 people");
  });
});
