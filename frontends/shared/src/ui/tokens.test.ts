import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const tokens = readFileSync(resolve(here, "tokens.css"), "utf8");

describe("tokens.css", () => {
  it("declares color-scheme for both themes so form controls follow the theme", () => {
    expect(tokens).toMatch(/:root\s*{[^}]*color-scheme:\s*light;/);
    expect(tokens).toMatch(/\[data-theme=["']dark["']\][^}]*color-scheme:\s*dark;/);
  });
});
