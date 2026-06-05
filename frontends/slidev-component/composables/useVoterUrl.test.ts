import { describe, it, expect, afterEach } from "vitest";
import { configs, __setFrontmatter } from "../test/slidev-client-stub";
import { useVoterUrl } from "./useVoterUrl";

afterEach(() => {
  // Reset the shared stub state between cases.
  for (const k of Object.keys(configs)) delete configs[k];
  __setFrontmatter({});
});

describe("useVoterUrl", () => {
  it("uses pollServer from slide frontmatter", () => {
    __setFrontmatter({ pollServer: "https://fm.example.test" });
    const url = useVoterUrl("my-poll");
    expect(url.value).toBe("https://fm.example.test/my-poll");
  });

  it("falls back to headmatter configs.pollServer when no frontmatter", () => {
    configs.pollServer = "https://hm.example.test";
    const url = useVoterUrl("my-poll");
    expect(url.value).toBe("https://hm.example.test/my-poll");
  });

  it("prefers frontmatter over headmatter configs", () => {
    __setFrontmatter({ pollServer: "https://fm.example.test" });
    configs.pollServer = "https://hm.example.test";
    const url = useVoterUrl("my-poll");
    expect(url.value).toBe("https://fm.example.test/my-poll");
  });

  it("strips a trailing slash from the host", () => {
    __setFrontmatter({ pollServer: "https://fm.example.test/" });
    const url = useVoterUrl("my-poll");
    expect(url.value).toBe("https://fm.example.test/my-poll");
  });

  it("falls back to window.location.origin when no pollServer is set", () => {
    // jsdom default origin is http://localhost
    const url = useVoterUrl("my-poll");
    expect(url.value).toBe(`${window.location.origin}/my-poll`);
  });

  it("accepts a getter for a reactive slug", async () => {
    const { ref } = await import("vue");
    __setFrontmatter({ pollServer: "https://fm.example.test" });
    const slug = ref("a");
    const url = useVoterUrl(() => slug.value);
    expect(url.value).toBe("https://fm.example.test/a");
    slug.value = "b";
    expect(url.value).toBe("https://fm.example.test/b");
  });
});
