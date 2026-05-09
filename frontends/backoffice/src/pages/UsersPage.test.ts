import { mount, flushPromises } from "@vue/test-utils";
import { describe, it, expect, vi } from "vitest";
import UsersPage from "./UsersPage.vue";

function makeClient(over = {}) {
  return {
    listUsers: vi.fn().mockResolvedValue([
      { username: "alice", displayName: "Alice", createdAt: "2026-05-09T00:00:00Z" }
    ]),
    createUser: vi.fn().mockResolvedValue({
      username: "bob", displayName: "Bob", createdAt: "2026-05-09T00:00:00Z"
    }),
    ...over
  } as any;
}

describe("UsersPage", () => {
  it("lists users on mount", async () => {
    const apiClient = makeClient();
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();
    expect(wrapper.text()).toContain("alice");
    expect(wrapper.text()).toContain("Alice");
  });

  it("creates user and refreshes list", async () => {
    const apiClient = makeClient();
    const wrapper = mount(UsersPage, { props: { apiClient } });
    await flushPromises();

    await wrapper.find('[data-testid="users-username"]').setValue("bob");
    await wrapper.find('[data-testid="users-password"]').setValue("another-strong-pw");
    await wrapper.find('[data-testid="users-displayname"]').setValue("Bob");
    await wrapper.find('[data-testid="users-form"]').trigger("submit.prevent");
    await flushPromises();

    expect(apiClient.createUser).toHaveBeenCalledWith({
      username: "bob",
      password: "another-strong-pw",
      displayName: "Bob"
    });
    expect(apiClient.listUsers).toHaveBeenCalledTimes(2);
  });

  it("surfaces USERNAME_TAKEN error", async () => {
    const { AdminApiError } = await import("../lib/admin-api");
    const apiClient = makeClient({
      createUser: vi.fn().mockRejectedValue(
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
    await wrapper.find('[data-testid="users-displayname"]').setValue("Alice 2");
    await wrapper.find('[data-testid="users-form"]').trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.find('[data-testid="users-error"]').text()).toContain("already taken");
  });
});
