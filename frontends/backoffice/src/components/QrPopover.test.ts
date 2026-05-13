import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import QrPopover from "./QrPopover.vue";

describe("QrPopover", () => {
  it("renders the trigger label and shows the QR on hover", async () => {
    const wrapper = mount(QrPopover, {
      props: { pollId: "p1", slug: "talk", size: 120 }
    });
    expect(wrapper.find('[data-testid="poll-qr-img"]').exists()).toBe(false);
    expect(wrapper.text()).toContain("QR");

    await wrapper.get('[data-testid="qr-popover-trigger"]').trigger("mouseenter");
    expect(wrapper.find('[data-testid="poll-qr-img"]').exists()).toBe(true);
  });
});
