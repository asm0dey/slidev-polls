import { describe, it, expect, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import UserMenu from "./UserMenu.vue";

describe("UserMenu", () => {
  it("calls apiClient.logout and pushes to /login", async () => {
    const logout = vi.fn().mockResolvedValue(undefined);
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/login", name: "login", component: { template: "<div/>" } },
        { path: "/polls", component: { template: "<div/>" } }
      ]
    });
    await router.push("/polls");
    await router.isReady();

    const wrapper = mount(UserMenu, {
      props: { username: "alice", apiClient: { logout } as never },
      global: { plugins: [router] }
    });
    await wrapper.get('[data-testid="user-menu-trigger"]').trigger("click");
    await wrapper.get('[data-testid="user-menu-logout"]').trigger("click");
    await new Promise((r) => setTimeout(r, 0));

    expect(logout).toHaveBeenCalledOnce();
    expect(router.currentRoute.value.name).toBe("login");
  });
});
