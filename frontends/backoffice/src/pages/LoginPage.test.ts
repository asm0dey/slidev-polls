import { describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import LoginPage from "./LoginPage.vue";
import type { AdminApiClient } from "../lib/admin-api";
import { AdminApiError } from "../lib/admin-api";

// Backs the frontend half of @TS-001 — the presenter signs in, the
// session cookie is set by the server (we just call POST /api/admin/login
// and trust the cookie), and an authentication failure surfaces as a
// non-generic message per Principle VI.

function makeFake(overrides: Partial<AdminApiClient> = {}): AdminApiClient {
  return {
    login: vi.fn().mockResolvedValue(undefined),
    ...overrides
  } as unknown as AdminApiClient;
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/login", name: "login", component: { template: "<div/>" } },
      { path: "/polls", name: "polls", component: { template: "<div/>" } }
    ]
  });
}

async function mountLogin(client: AdminApiClient) {
  const router = makeRouter();
  await router.push("/login");
  await router.isReady();
  const wrapper = mount(LoginPage, {
    global: { plugins: [router] },
    props: { apiClient: client }
  });
  return { wrapper, router };
}

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, user: string, pass: string) {
  await wrapper.find('input[data-testid="login-username"]').setValue(user);
  await wrapper.find('input[data-testid="login-password"]').setValue(pass);
  await wrapper.find('form[data-testid="login-form"]').trigger("submit.prevent");
}

describe("LoginPage", () => {
  it("submits credentials and redirects to /polls on success", async () => {
    const login = vi.fn().mockResolvedValue(undefined);
    const client = makeFake({ login });
    const { wrapper, router } = await mountLogin(client);

    await fillAndSubmit(wrapper, "alice", "correct-horse");
    await flushPromises();

    expect(login).toHaveBeenCalledWith({ username: "alice", password: "correct-horse" });
    expect(router.currentRoute.value.path).toBe("/polls");
  });

  it("inputs have semantic labels and autocomplete hints", async () => {
    const client = makeFake();
    const { wrapper } = await mountLogin(client);

    const username = wrapper.get('[data-testid="login-username"]');
    expect(username.attributes("autocomplete")).toBe("username");
    expect(username.attributes("name")).toBe("username");

    const password = wrapper.get('[data-testid="login-password"]');
    expect(password.attributes("autocomplete")).toBe("current-password");
    expect(password.attributes("name")).toBe("password");

    expect(wrapper.find('label[for="login-username"]').text()).toContain("Username");
    expect(wrapper.find('label[for="login-password"]').text()).toContain("Password");
  });

  it("surfaces an actionable error when authentication fails", async () => {
    const login = vi
      .fn()
      .mockRejectedValue(
        new AdminApiError(
          401,
          { code: "AUTH_REQUIRED", message: "Bad credentials" },
          "Bad credentials"
        )
      );
    const client = makeFake({ login });
    const { wrapper, router } = await mountLogin(client);

    await fillAndSubmit(wrapper, "alice", "wrong");
    await flushPromises();

    const error = wrapper.find('[data-testid="login-error"]');
    expect(error.exists()).toBe(true);
    expect(error.text().toLowerCase()).not.toContain("something went wrong");
    expect(router.currentRoute.value.path).toBe("/login");
  });
});
