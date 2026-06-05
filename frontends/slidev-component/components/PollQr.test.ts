import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { configs, __setFrontmatter } from "../test/slidev-client-stub";

// qr-code-styling touches canvas APIs jsdom lacks. Mock it so we can assert the
// component drives the lib correctly (constructor opts, append, update).
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

import PollQr from "./PollQr.vue";

beforeEach(() => {
  ctorSpy.mockClear();
  appendSpy.mockClear();
  updateSpy.mockClear();
  __setFrontmatter({ pollServer: "https://example.test" });
});

afterEach(() => {
  for (const k of Object.keys(configs)) delete configs[k];
  __setFrontmatter({});
});

describe("PollQr", () => {
  it("renders the QR container and the voter URL text", async () => {
    const w = mount(PollQr, { props: { slug: "my-poll" }, attachTo: document.body });
    await flushPromises();
    expect(w.find("[data-testid='poll-qr']").exists()).toBe(true);
    expect(w.text()).toContain("https://example.test/my-poll");
    w.unmount();
  });

  it("constructs QRCodeStyling with svg + rounded dots and the resolved voter URL, then appends an SVG", async () => {
    const w = mount(PollQr, { props: { slug: "my-poll" }, attachTo: document.body });
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
    expect(w.find("[data-testid='qr-svg']").exists()).toBe(true);
    w.unmount();
  });

  it("calls update() with the new URL when slug changes", async () => {
    const w = mount(PollQr, { props: { slug: "a" }, attachTo: document.body });
    await flushPromises();
    expect(updateSpy).not.toHaveBeenCalled();

    await w.setProps({ slug: "b" });
    await flushPromises();
    expect(updateSpy).toHaveBeenCalledTimes(1);
    expect(updateSpy.mock.calls[0][0]).toMatchObject({ data: "https://example.test/b" });
    expect(w.text()).toContain("https://example.test/b");
    w.unmount();
  });
});
