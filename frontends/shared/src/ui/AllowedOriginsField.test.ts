import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import AllowedOriginsField from "./AllowedOriginsField.vue";

describe("AllowedOriginsField", () => {
  it("renders existing origins as chips", () => {
    const w = mount(AllowedOriginsField, {
      props: { modelValue: ["https://a.example", "https://b.example"] }
    });
    const chips = w.findAll("[data-testid='origin-chip']");
    expect(chips).toHaveLength(2);
    expect(chips[0].text()).toContain("https://a.example");
  });

  it("adds an origin on Enter when valid", async () => {
    const w = mount(AllowedOriginsField, {
      props: { modelValue: [] }
    });
    const input = w.find<HTMLInputElement>("input.sp-aof-input");
    await input.setValue("https://example.com");
    await input.trigger("keydown.enter");
    expect(w.emitted("update:modelValue")?.[0]).toEqual([["https://example.com"]]);
  });

  it("normalizes by stripping trailing slash on add", async () => {
    const w = mount(AllowedOriginsField, {
      props: { modelValue: [] }
    });
    const input = w.find<HTMLInputElement>("input.sp-aof-input");
    await input.setValue("https://example.com/");
    await input.trigger("keydown.enter");
    expect(w.emitted("update:modelValue")?.[0]).toEqual([["https://example.com"]]);
  });

  it("shows validation banner for invalid origin and does not emit", async () => {
    const w = mount(AllowedOriginsField, {
      props: { modelValue: [] }
    });
    const input = w.find<HTMLInputElement>("input.sp-aof-input");
    await input.setValue("example.com");
    await input.trigger("keydown.enter");
    expect(w.emitted("update:modelValue")).toBeUndefined();
    expect(w.find("[data-testid='aof-error']").exists()).toBe(true);
    expect(w.find("[data-testid='aof-error']").text()).toMatch(/scheme/i);
  });

  it("removes origin via chip remove button", async () => {
    const w = mount(AllowedOriginsField, {
      props: { modelValue: ["https://a.example", "https://b.example"] }
    });
    await w.findAll("[data-testid='origin-chip'] button")[0].trigger("click");
    expect(w.emitted("update:modelValue")?.[0]).toEqual([["https://b.example"]]);
  });

  it("renders pre-existing invalid origins as red chips", async () => {
    const w = mount(AllowedOriginsField, {
      props: { modelValue: ["example.org"] }
    });
    await nextTick();
    const chip = w.find("[data-testid='origin-chip']");
    expect(chip.attributes("data-invalid")).toBeDefined();
  });
});
