import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import QrPreview from "./QrPreview.vue";

// @TS-026: the QR endpoint encodes the poll's public URL. The component
// only renders the <img>; the contract that the bytes decode to the slug
// is pinned by QrEndpointIT (T045) on the backend.

describe("QrPreview", () => {
  it("renders an img sourced from /api/admin/polls/{id}/qr.png", () => {
    const wrapper = mount(QrPreview, { props: { pollId: "abc-123" } });
    const img = wrapper.find<HTMLImageElement>("img");
    expect(img.exists()).toBe(true);
    expect(img.attributes("src")).toBe("/api/admin/polls/abc-123/qr.png");
  });

  it("URL-encodes the poll id so a stray slash can't traverse the path", () => {
    const wrapper = mount(QrPreview, { props: { pollId: "weird/id" } });
    const img = wrapper.find("img");
    expect(img.attributes("src")).toBe("/api/admin/polls/weird%2Fid/qr.png");
  });

  it("includes a meaningful alt attribute for accessibility", () => {
    const wrapper = mount(QrPreview, { props: { pollId: "p1", slug: "my-talk" } });
    const img = wrapper.find("img");
    expect(img.attributes("alt")).toContain("my-talk");
  });
});
