import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import Menu from "./Menu.vue";

describe("Menu", () => {
  it("shows items only after the trigger is clicked", async () => {
    const wrapper = mount(Menu, {
      props: { label: "More", items: [{ key: "a", label: "First" }] }
    });
    expect(wrapper.find('[data-testid="menu-items"]').exists()).toBe(false);
    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    expect(wrapper.find('[data-testid="menu-items"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("First");
  });

  it("emits the selected key and closes", async () => {
    const wrapper = mount(Menu, {
      props: { label: "More", items: [{ key: "a", label: "First" }] }
    });
    await wrapper.get('[data-testid="menu-trigger"]').trigger("click");
    await wrapper.get('[data-testid="menu-item-a"]').trigger("click");
    expect(wrapper.emitted("select")?.[0]).toEqual(["a"]);
    expect(wrapper.find('[data-testid="menu-items"]').exists()).toBe(false);
  });
});
