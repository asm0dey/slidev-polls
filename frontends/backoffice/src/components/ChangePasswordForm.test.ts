import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import ChangePasswordForm from "./ChangePasswordForm.vue";

function client(overrides: Record<string, unknown> = {}) {
  return { changePassword: vi.fn().mockResolvedValue(undefined), ...overrides };
}

describe("ChangePasswordForm", () => {
  it("submits current + new password", async () => {
    const api = client();
    const wrapper = mount(ChangePasswordForm, { props: { apiClient: api } });
    await wrapper.find('input[name="currentPassword"]').setValue("old-password-12");
    await wrapper.find('input[name="newPassword"]').setValue("new-password-3456");
    await wrapper.find("form").trigger("submit.prevent");
    expect(api.changePassword).toHaveBeenCalledWith("old-password-12", "new-password-3456");
  });

  it("shows an error when the current password is wrong", async () => {
    const api = client({
      changePassword: vi
        .fn()
        .mockRejectedValue({ code: "FORBIDDEN", message: "current password is incorrect" })
    });
    const wrapper = mount(ChangePasswordForm, { props: { apiClient: api } });
    await wrapper.find('input[name="currentPassword"]').setValue("wrong-password-12");
    await wrapper.find('input[name="newPassword"]').setValue("new-password-3456");
    await wrapper.find("form").trigger("submit.prevent");
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain("current password");
  });

  it("shows success message and clears inputs after a successful submit", async () => {
    const api = client();
    const wrapper = mount(ChangePasswordForm, { props: { apiClient: api } });
    await wrapper.find('input[name="currentPassword"]').setValue("old-password-12");
    await wrapper.find('input[name="newPassword"]').setValue("new-password-3456");
    await wrapper.find("form").trigger("submit.prevent");
    await flushPromises();
    expect(wrapper.text()).toContain("Password changed");
    expect((wrapper.find('input[name="currentPassword"]').element as HTMLInputElement).value).toBe(
      ""
    );
    expect((wrapper.find('input[name="newPassword"]').element as HTMLInputElement).value).toBe("");
  });
});
