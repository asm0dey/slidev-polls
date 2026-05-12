import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import ConfirmDialog from "./ConfirmDialog.vue";

describe("ConfirmDialog", () => {
  it("emits confirm when the confirm button is clicked", async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: "Clear votes?", confirmLabel: "Clear", tone: "danger" },
      attachTo: document.body
    });
    await wrapper.get('[data-testid="confirm-dialog-confirm"]').trigger("click");
    expect(wrapper.emitted("confirm")).toHaveLength(1);
    wrapper.unmount();
  });

  it("emits cancel when the cancel button is clicked", async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: "x" },
      attachTo: document.body
    });
    await wrapper.get('[data-testid="confirm-dialog-cancel"]').trigger("click");
    expect(wrapper.emitted("cancel")).toHaveLength(1);
    wrapper.unmount();
  });

  it("does not open the dialog when open is false", () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: false, title: "x" },
      attachTo: document.body
    });
    const dialog = wrapper.find("dialog");
    expect(dialog.exists()).toBe(true);
    expect((dialog.element as HTMLDialogElement).open).toBe(false);
    wrapper.unmount();
  });
});
