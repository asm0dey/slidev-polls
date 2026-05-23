import { mount, flushPromises } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import PollCollaborators from "./PollCollaborators.vue";

function api(overrides: Record<string, unknown> = {}) {
  return {
    listCollaborators: vi
      .fn()
      .mockResolvedValue([{ username: "colla", createdAt: "2026-01-01T00:00:00Z" }]),
    addCollaborator: vi
      .fn()
      .mockResolvedValue({ username: "dave", createdAt: "2026-02-01T00:00:00Z" }),
    removeCollaborator: vi.fn().mockResolvedValue(undefined),
    ...overrides
  };
}

describe("PollCollaborators", () => {
  it("lists existing collaborators", async () => {
    const wrapper = mount(PollCollaborators, { props: { pollId: "p1", apiClient: api() } });
    await flushPromises();
    expect(wrapper.text()).toContain("colla");
  });

  it("adds a collaborator by username", async () => {
    const a = api();
    const wrapper = mount(PollCollaborators, { props: { pollId: "p1", apiClient: a } });
    await flushPromises();
    await wrapper.find('input[name="newCollaborator"]').setValue("dave");
    await wrapper.find("form").trigger("submit.prevent");
    expect(a.addCollaborator).toHaveBeenCalledWith("p1", "dave");
  });

  it("shows a conflict error when the user is already a collaborator", async () => {
    const a = api({
      addCollaborator: vi
        .fn()
        .mockRejectedValue({ code: "COLLABORATOR_EXISTS", message: "already a collaborator" })
    });
    const wrapper = mount(PollCollaborators, { props: { pollId: "p1", apiClient: a } });
    await flushPromises();
    await wrapper.find('input[name="newCollaborator"]').setValue("colla");
    await wrapper.find("form").trigger("submit.prevent");
    await flushPromises();
    expect(wrapper.text()).toContain("already a collaborator");
  });

  it("removes a collaborator and refreshes the list", async () => {
    const a = api();
    const wrapper = mount(PollCollaborators, { props: { pollId: "p1", apiClient: a } });
    await flushPromises();

    const removeBtn = wrapper.find('button[aria-label="Remove colla"]');
    expect(removeBtn.exists()).toBe(true);
    await removeBtn.trigger("click");
    await flushPromises();

    expect(a.removeCollaborator).toHaveBeenCalledWith("p1", "colla");
    // listCollaborators called once on mount + once after remove
    expect(a.listCollaborators).toHaveBeenCalledTimes(2);
  });
});
