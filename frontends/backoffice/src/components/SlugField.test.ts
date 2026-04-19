import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import SlugField from "./SlugField.vue";

// Mirrors the server-side SlugValidator (regex ^[a-z0-9]+(-[a-z0-9]+)*$,
// length 3–40) and ReservedSlugs (admin, api, assets, static, j, login,
// logout) so the presenter sees the same rejection client-side that the
// backend would issue per @TS-011 / @TS-012, without a round-trip.

function mountField(initial = "") {
  return mount(SlugField, {
    props: { modelValue: initial }
  });
}

async function setSlug(wrapper: ReturnType<typeof mountField>, value: string) {
  const input = wrapper.find('input[data-testid="slug-input"]');
  await input.setValue(value);
}

describe("SlugField", () => {
  it("renders an input bound to the modelValue prop", () => {
    const wrapper = mountField("my-talk");
    const input = wrapper.find<HTMLInputElement>('input[data-testid="slug-input"]');
    expect(input.exists()).toBe(true);
    expect(input.element.value).toBe("my-talk");
  });

  it("emits update:modelValue when the input changes", async () => {
    const wrapper = mountField("");
    await setSlug(wrapper, "new-slug");
    const events = wrapper.emitted("update:modelValue");
    expect(events).toBeTruthy();
    expect(events?.[events.length - 1]).toEqual(["new-slug"]);
  });

  it("shows no error message for a valid slug", async () => {
    const wrapper = mountField("");
    await setSlug(wrapper, "quickstart-demo");
    expect(wrapper.find('[data-testid="slug-error"]').exists()).toBe(false);
  });

  // @TS-011: invalid format examples — every row should surface a
  // SLUG_INVALID-equivalent client-side hint.
  it.each([
    ["Ab", "length"],
    ["ab", "length"],
    ["-leading", "format"],
    ["trailing-", "format"],
    ["double--dash", "format"],
    ["UPPER", "format"],
    ["has space", "format"],
    ["way-too-long-slug-exceeding-forty-chars-limit", "length"]
  ])("flags %s as invalid (%s rule)", async (slug) => {
    const wrapper = mountField("");
    await setSlug(wrapper, slug);
    const error = wrapper.find('[data-testid="slug-error"]');
    expect(error.exists()).toBe(true);
    expect(error.text().length).toBeGreaterThan(0);
    // Reserved hint is the wrong category for these inputs.
    expect(error.text().toLowerCase()).not.toContain("reserved");
  });

  // @TS-012: reserved slugs surface a distinct hint so the presenter
  // doesn't think the format is wrong.
  it.each(["admin", "api", "assets", "static", "j", "login", "logout"])(
    "flags %s as reserved",
    async (slug) => {
      const wrapper = mountField("");
      await setSlug(wrapper, slug);
      const error = wrapper.find('[data-testid="slug-error"]');
      expect(error.exists()).toBe(true);
      expect(error.text().toLowerCase()).toContain("reserved");
    }
  );

  it("emits valid:true for a well-formed, non-reserved slug", async () => {
    const wrapper = mountField("");
    await setSlug(wrapper, "fav-ide");
    const events = wrapper.emitted<[boolean]>("update:valid");
    expect(events).toBeTruthy();
    expect(events?.[events.length - 1]?.[0]).toBe(true);
  });

  it("emits valid:false when the slug is invalid", async () => {
    const wrapper = mountField("");
    await setSlug(wrapper, "UPPER");
    const events = wrapper.emitted<[boolean]>("update:valid");
    expect(events).toBeTruthy();
    expect(events?.[events.length - 1]?.[0]).toBe(false);
  });

  it("emits valid:false for a reserved slug", async () => {
    const wrapper = mountField("");
    await setSlug(wrapper, "login");
    const events = wrapper.emitted<[boolean]>("update:valid");
    expect(events).toBeTruthy();
    expect(events?.[events.length - 1]?.[0]).toBe(false);
  });
});
