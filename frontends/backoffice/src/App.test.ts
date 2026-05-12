import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import App from "./App.vue";
import PollListPage from "./pages/PollListPage.vue";

async function mountApp() {
  const router = createRouter({
    history: createMemoryHistory("/"),
    routes: [
      { path: "/", redirect: "/polls" },
      { path: "/polls", name: "polls", component: PollListPage },
      { path: "/users", name: "users", component: { template: "<div/>" } }
    ]
  });
  await router.push("/polls");
  await router.isReady();
  return mount(App, { global: { plugins: [router] } });
}

describe("App sidebar", () => {
  it("does not link to the dead /deck-tokens route", async () => {
    const wrapper = await mountApp();
    const hrefs = wrapper.findAll("aside.bo-sidebar a").map((a) => a.attributes("href"));
    expect(hrefs).not.toContain("/deck-tokens");
  });
});
