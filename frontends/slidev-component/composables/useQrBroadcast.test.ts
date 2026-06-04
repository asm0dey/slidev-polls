import { describe, it, expect } from "vitest";
import { useQrBroadcast } from "./useQrBroadcast";

// BroadcastChannel delivers to *other* instances asynchronously; a macrotask
// tick lets the queued message land before we assert.
function tick(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

describe("useQrBroadcast", () => {
  it("propagates the open state to another instance on the same key", async () => {
    const presenter = useQrBroadcast("https://e.test/talk");
    const audience = useQrBroadcast("https://e.test/talk");
    expect(audience.open.value).toBe(false);

    presenter.set(true);
    await tick();
    expect(audience.open.value).toBe(true);

    presenter.set(false);
    await tick();
    expect(audience.open.value).toBe(false);

    presenter.stop();
    audience.stop();
  });

  it("ignores messages scoped to a different key", async () => {
    const a = useQrBroadcast("https://e.test/poll-a");
    const b = useQrBroadcast("https://e.test/poll-b");

    a.set(true);
    await tick();
    expect(b.open.value).toBe(false);

    a.stop();
    b.stop();
  });

  it("stops receiving updates after stop()", async () => {
    const presenter = useQrBroadcast("https://e.test/stopme");
    const audience = useQrBroadcast("https://e.test/stopme");

    audience.stop();
    presenter.set(true);
    await tick();
    expect(audience.open.value).toBe(false);

    presenter.stop();
  });
});
