import { mount, flushPromises } from "@vue/test-utils";
import { describe, it, expect, vi } from "vitest";
import { createRouter, createMemoryHistory } from "vue-router";
import SetupPage from "./SetupPage.vue";

function makeClient(overrides = {}) {
  return {
    runSetup: vi.fn().mockResolvedValue({
      username: "alice",
      displayName: "Alice",
      createdAt: "2026-05-09T00:00:00Z"
    }),
    login: vi.fn().mockResolvedValue(undefined),
    ...overrides
  } as any;
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", redirect: "/polls" },
      { path: "/polls", component: { template: "<div>polls</div>" } },
      { path: "/setup", component: SetupPage }
    ]
  });
}

describe("SetupPage", () => {
  it("submits setup, then logs in and routes to /polls", async () => {
    const apiClient = makeClient();
    const router = makeRouter();
    await router.push("/setup");
    const wrapper = mount(SetupPage, {
      props: { apiClient },
      global: { plugins: [router] }
    });

    await wrapper.find('[data-testid="setup-username"]').setValue("alice");
    await wrapper.find('[data-testid="setup-password"]').setValue("correct-horse-battery");
    await wrapper.find('[data-testid="setup-displayname"]').setValue("Alice");
    await wrapper.find('[data-testid="setup-form"]').trigger("submit.prevent");
    await flushPromises();

    expect(apiClient.runSetup).toHaveBeenCalledWith({
      username: "alice",
      password: "correct-horse-battery",
      displayName: "Alice"
    });
    expect(apiClient.login).toHaveBeenCalledWith({
      username: "alice",
      password: "correct-horse-battery"
    });
    expect(router.currentRoute.value.path).toBe("/polls");
  });

  it("surfaces server validation errors", async () => {
    const { AdminApiError } = await import("../lib/admin-api");
    const apiClient = makeClient({
      runSetup: vi.fn().mockRejectedValue(
        new AdminApiError(
          400,
          { code: "VALIDATION_FAILED", message: "password too short" } as any,
          "password too short"
        )
      )
    });
    const router = makeRouter();
    await router.push("/setup");
    const wrapper = mount(SetupPage, {
      props: { apiClient },
      global: { plugins: [router] }
    });

    await wrapper.find('[data-testid="setup-username"]').setValue("alice");
    await wrapper.find('[data-testid="setup-password"]').setValue("short");
    await wrapper.find('[data-testid="setup-displayname"]').setValue("Alice");
    await wrapper.find('[data-testid="setup-form"]').trigger("submit.prevent");
    await flushPromises();

    expect(wrapper.find('[data-testid="setup-error"]').text()).toContain("password too short");
  });
});
