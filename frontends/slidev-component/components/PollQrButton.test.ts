import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";

// qr-code-styling internally touches canvas APIs that jsdom doesn't implement.
// Mock the module so unit tests stay environment-agnostic and we can assert
// the component drives the lib correctly (constructor opts, append, update).
const appendSpy = vi.fn();
const updateSpy = vi.fn();
const ctorSpy = vi.fn();
vi.mock("qr-code-styling", () => {
  class FakeQRCodeStyling {
    constructor(opts: unknown) {
      ctorSpy(opts);
    }
    append(el: HTMLElement) {
      appendSpy(el);
      const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      svg.setAttribute("viewBox", "0 0 100 100");
      svg.setAttribute("data-testid", "qr-svg");
      el.appendChild(svg);
    }
    update(opts: unknown) {
      updateSpy(opts);
    }
  }
  return { default: FakeQRCodeStyling };
});

import PollQrButton from "./PollQrButton.vue";

beforeEach(() => {
  ctorSpy.mockClear();
  appendSpy.mockClear();
  updateSpy.mockClear();
});

describe("PollQrButton", () => {
  it("renders a toggle button with an aria-label mentioning QR", () => {
    const w = mount(PollQrButton, {
      props: { voterUrl: "https://example.test/my-poll" },
      attachTo: document.body
    });
    const btn = w.find("[data-testid='poll-qr-toggle']");
    expect(btn.exists()).toBe(true);
    expect(btn.attributes("aria-label")).toMatch(/qr/i);
    w.unmount();
  });

  it("does not render the overlay until clicked", () => {
    const w = mount(PollQrButton, {
      props: { voterUrl: "https://example.test/my-poll" },
      attachTo: document.body
    });
    expect(document.querySelector("[data-testid='poll-qr-overlay']")).toBeNull();
    expect(ctorSpy).not.toHaveBeenCalled();
    w.unmount();
  });

  it("constructs QRCodeStyling with rounded dots and the voter URL, then mounts an SVG", async () => {
    const w = mount(PollQrButton, {
      props: { voterUrl: "https://example.test/my-poll" },
      attachTo: document.body
    });
    await w.find("[data-testid='poll-qr-toggle']").trigger("click");
    await flushPromises();

    expect(ctorSpy).toHaveBeenCalledTimes(1);
    const opts = ctorSpy.mock.calls[0][0] as {
      type: string;
      data: string;
      dotsOptions?: { type?: string };
    };
    expect(opts.type).toBe("svg");
    expect(opts.data).toBe("https://example.test/my-poll");
    expect(opts.dotsOptions?.type).toBe("rounded");

    expect(appendSpy).toHaveBeenCalledTimes(1);
    const overlay = document.querySelector("[data-testid='poll-qr-overlay']");
    expect(overlay).not.toBeNull();
    expect(overlay!.querySelector("[data-testid='qr-svg']")).not.toBeNull();
    expect(overlay!.textContent).toContain("https://example.test/my-poll");

    w.unmount();
  });

  it("calls update() when voterUrl changes while the overlay is open", async () => {
    const w = mount(PollQrButton, {
      props: { voterUrl: "https://example.test/a" },
      attachTo: document.body
    });
    await w.find("[data-testid='poll-qr-toggle']").trigger("click");
    await flushPromises();
    expect(updateSpy).not.toHaveBeenCalled();

    await w.setProps({ voterUrl: "https://example.test/b" });
    await flushPromises();
    expect(updateSpy).toHaveBeenCalledTimes(1);
    expect(updateSpy.mock.calls[0][0]).toMatchObject({ data: "https://example.test/b" });

    w.unmount();
  });

  it("closes the overlay when the backdrop is clicked", async () => {
    const w = mount(PollQrButton, {
      props: { voterUrl: "https://example.test/my-poll" },
      attachTo: document.body
    });
    await w.find("[data-testid='poll-qr-toggle']").trigger("click");
    await flushPromises();
    const overlay = document.querySelector("[data-testid='poll-qr-overlay']") as HTMLElement;
    overlay.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    await flushPromises();
    expect(document.querySelector("[data-testid='poll-qr-overlay']")).toBeNull();
    w.unmount();
  });

  it("closes the overlay on Escape", async () => {
    const w = mount(PollQrButton, {
      props: { voterUrl: "https://example.test/my-poll" },
      attachTo: document.body
    });
    await w.find("[data-testid='poll-qr-toggle']").trigger("click");
    await flushPromises();
    document.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape" }));
    await flushPromises();
    expect(document.querySelector("[data-testid='poll-qr-overlay']")).toBeNull();
    w.unmount();
  });
});
