import { describe, expect, it } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import LandingPage from "./LandingPage.vue";

// Coverage for the voter landing page's slug-submit flow. Validation mirrors SlugValidator so
// the page never routes to a slug the server is about to reject; the tests pin the shape of
// that validation so a divergence between client and server hurts here first.

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: "/", name: "landing", component: LandingPage },
      { path: "/:slug", name: "poll", component: { template: "<div/>" } }
    ]
  });
}

async function mountLanding() {
  const router = makeRouter();
  await router.push("/");
  await router.isReady();
  const wrapper = mount(LandingPage, { global: { plugins: [router] } });
  return { wrapper, router };
}

async function fillAndSubmit(wrapper: ReturnType<typeof mount>, slug: string) {
  await wrapper.find('[data-testid="landing-slug"]').setValue(slug);
  await wrapper.find('[data-testid="landing-form"]').trigger("submit.prevent");
}

describe("LandingPage", () => {
  it("routes to /{slug} for a well-formed slug", async () => {
    const { wrapper, router } = await mountLanding();
    await fillAndSubmit(wrapper, "my-talk");
    await flushPromises();

    expect(router.currentRoute.value.name).toBe("poll");
    expect(router.currentRoute.value.params.slug).toBe("my-talk");
  });

  it("rejects an empty submission with an actionable message", async () => {
    const { wrapper, router } = await mountLanding();
    await fillAndSubmit(wrapper, "");
    await flushPromises();

    const err = wrapper.find('[data-testid="landing-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text().toLowerCase()).toContain("enter");
    expect(router.currentRoute.value.name).toBe("landing");
  });

  it("rejects an UPPER-case slug with a format-specific message", async () => {
    const { wrapper, router } = await mountLanding();
    await fillAndSubmit(wrapper, "UPPER");
    await flushPromises();

    const err = wrapper.find('[data-testid="landing-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text().toLowerCase()).toContain("lowercase");
    expect(router.currentRoute.value.name).toBe("landing");
  });

  it("rejects a too-short slug (<3 chars)", async () => {
    const { wrapper, router } = await mountLanding();
    await fillAndSubmit(wrapper, "ab");
    await flushPromises();

    expect(wrapper.find('[data-testid="landing-error"]').exists()).toBe(true);
    expect(router.currentRoute.value.name).toBe("landing");
  });
});
