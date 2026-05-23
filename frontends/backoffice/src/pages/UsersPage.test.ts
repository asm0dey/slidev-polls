import { mount, flushPromises } from "@vue/test-utils";
import { describe, it, expect, vi, beforeEach } from "vitest";
import UsersPage from "./UsersPage.vue";

const ALICE = { username: "alice", createdAt: "2026-05-09T00:00:00Z", blocked: false };
const BOB = { username: "bob", createdAt: "2026-05-09T00:00:00Z", blocked: false };
const CAROL = { username: "carol", createdAt: "2026-05-09T00:00:00Z", blocked: true };

// jsdom does not implement HTMLDialogElement showModal()/close() — shim them.
beforeEach(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
    };
  }
  if (!HTMLDialogElement.prototype.close) {
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
  }
});

function makeClient(over: Record<string, unknown> = {}) {
  return {
    listUsers: vi.fn().mockResolvedValue([ALICE]),
    createUser: vi.fn().mockResolvedValue(BOB),
    getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: false }),
    resetUserPassword: vi.fn().mockResolvedValue(undefined),
    blockUser: vi.fn().mockResolvedValue(undefined),
    unblockUser: vi.fn().mockResolvedValue(undefined),
    ...over
  } as any;
}

describe("UsersPage", () => {
  it("lists users on mount", async () => {
    const apiClient = makeClient();
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();
    expect(wrapper.text()).toContain("alice");
  });

  it("creates user and refreshes list", async () => {
    const apiClient = makeClient();
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="users-username"]').setValue("bob");
    await wrapper.find('[data-testid="users-password"]').setValue("another-strong-pw");
    await wrapper.find('[data-testid="users-form"]').trigger("submit.prevent");
    await flushPromises();

    expect(apiClient.createUser).toHaveBeenCalledWith({
      username: "bob",
      password: "another-strong-pw"
    });
    expect(apiClient.listUsers).toHaveBeenCalledTimes(2);
  });

  it("formats createdAt with the relative helper", async () => {
    const apiClient = makeClient({
      listUsers: vi
        .fn()
        .mockResolvedValue([
          { username: "alice", createdAt: new Date().toISOString(), blocked: false }
        ])
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();
    expect(wrapper.text()).toMatch(/today \d{2}:\d{2}/);
  });

  it("surfaces USERNAME_TAKEN error", async () => {
    const { AdminApiError } = await import("../lib/admin-api");
    const apiClient = makeClient({
      createUser: vi
        .fn()
        .mockRejectedValue(
          new AdminApiError(
            409,
            { code: "USERNAME_TAKEN", message: "username already taken: alice" } as any,
            "username already taken: alice"
          )
        )
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="users-username"]').setValue("alice");
    await wrapper.find('[data-testid="users-password"]').setValue("another-strong-pw");
    await wrapper.find('[data-testid="users-form"]').trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.find('[data-testid="users-error"]').text()).toContain("already taken");
  });

  // ── Admin controls visibility ────────────────────────────────────────────────

  it("shows Reset and Block controls for non-self users when isAdmin", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([BOB]),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    expect(wrapper.find('[data-testid="reset-password-bob"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="block-bob"]').exists()).toBe(true);
  });

  it("shows Unblock instead of Block when user is blocked", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([CAROL]),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    expect(wrapper.find('[data-testid="unblock-carol"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="block-carol"]').exists()).toBe(false);
  });

  it("hides admin controls when isAdmin is false", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([BOB]),
      getAccount: vi.fn().mockResolvedValue({ username: "viewer", isAdmin: false })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    expect(wrapper.find('[data-testid="reset-password-bob"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="block-bob"]').exists()).toBe(false);
  });

  it("hides admin controls for the current user's own row", async () => {
    const apiClient = makeClient({
      listUsers: vi
        .fn()
        .mockResolvedValue([
          { username: "admin", createdAt: "2026-05-09T00:00:00Z", blocked: false }
        ]),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    expect(wrapper.find('[data-testid="reset-password-admin"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="block-admin"]').exists()).toBe(false);
  });

  it("shows Blocked indicator on blocked rows", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([CAROL]),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    expect(wrapper.find('[data-testid="blocked-badge-carol"]').exists()).toBe(true);
  });

  // ── Admin actions ────────────────────────────────────────────────────────────

  it("clicking Block opens ConfirmDialog and does NOT call blockUser until confirmed", async () => {
    const listUsers = vi.fn().mockResolvedValue([BOB]);
    const blockUser = vi.fn().mockResolvedValue(undefined);
    const apiClient = makeClient({
      listUsers,
      blockUser,
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="block-bob"]').trigger("click");
    await flushPromises();

    // Dialog should appear, blockUser must not have been called yet
    expect(wrapper.find('[data-testid="confirm-dialog"]').exists()).toBe(true);
    expect(blockUser).not.toHaveBeenCalled();

    // Confirming calls blockUser and refreshes list
    await wrapper.find('[data-testid="confirm-dialog-confirm"]').trigger("click");
    await flushPromises();

    expect(blockUser).toHaveBeenCalledWith("bob");
    expect(listUsers).toHaveBeenCalledTimes(2);
  });

  it("cancelling the block ConfirmDialog does not call blockUser", async () => {
    const blockUser = vi.fn().mockResolvedValue(undefined);
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([BOB]),
      blockUser,
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="block-bob"]').trigger("click");
    await flushPromises();

    await wrapper.find('[data-testid="confirm-dialog-cancel"]').trigger("click");
    await flushPromises();

    expect(blockUser).not.toHaveBeenCalled();
    // Dialog element stays in DOM but must not have the "open" attribute
    expect(wrapper.find('[data-testid="confirm-dialog"]').attributes("open")).toBeUndefined();
  });

  it("clicking Unblock calls unblockUser then refreshes list", async () => {
    const listUsers = vi.fn().mockResolvedValue([CAROL]);
    const apiClient = makeClient({
      listUsers,
      unblockUser: vi.fn().mockResolvedValue(undefined),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="unblock-carol"]').trigger("click");
    await flushPromises();

    expect(apiClient.unblockUser).toHaveBeenCalledWith("carol");
    expect(listUsers).toHaveBeenCalledTimes(2);
  });

  it("submitting reset-password calls resetUserPassword with new password", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([BOB]),
      resetUserPassword: vi.fn().mockResolvedValue(undefined),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    // Reveal the reset-password form
    await wrapper.find('[data-testid="reset-password-bob"]').trigger("click");
    await flushPromises();

    await wrapper.find('[data-testid="reset-password-input-bob"]').setValue("new-strong-pass-123");
    await wrapper.find('[data-testid="reset-password-submit-bob"]').trigger("click");
    await flushPromises();

    expect(apiClient.resetUserPassword).toHaveBeenCalledWith("bob", "new-strong-pass-123");
  });

  // ── Error handling ───────────────────────────────────────────────────────────

  it("getAccount rejection on mount still renders user list and shows an error", async () => {
    const listUsers = vi.fn().mockResolvedValue([ALICE]);
    const apiClient = makeClient({
      listUsers,
      getAccount: vi.fn().mockRejectedValue(new Error("network error"))
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    // User list must still render
    expect(wrapper.text()).toContain("alice");

    // Error must be surfaced
    expect(wrapper.find('[data-testid="users-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="users-error"]').text()).toContain("network error");
  });

  it("listUsers rejection on mount shows an error banner", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockRejectedValue(new Error("network timeout")),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    expect(wrapper.find('[data-testid="users-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="users-error"]').text()).toContain("network timeout");
  });

  it("blockUser rejection displays an error message", async () => {
    const apiClient = makeClient({
      listUsers: vi.fn().mockResolvedValue([BOB]),
      blockUser: vi.fn().mockRejectedValue(new Error("block failed")),
      getAccount: vi.fn().mockResolvedValue({ username: "admin", isAdmin: true })
    });
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="block-bob"]').trigger("click");
    await flushPromises();

    // Confirm in the dialog
    await wrapper.find('[data-testid="confirm-dialog-confirm"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="users-error"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="users-error"]').text()).toContain("block failed");
  });
});
