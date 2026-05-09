import { describe, it, expect } from "vitest";
import { mount } from "@vue/test-utils";
import ResultsPanel from "./ResultsPanel.vue";

const sample = {
  prompt: "Pick one",
  options: [
    { id: "a", label: "React" },
    { id: "b", label: "Vue" },
    { id: "c", label: "Svelte" }
  ],
  tally: [
    { optionId: "a", count: 12 },
    { optionId: "b", count: 21 },
    { optionId: "c", count: 9 }
  ]
};

describe("ResultsPanel", () => {
  it("renders prompt and total", () => {
    const w = mount(ResultsPanel, {
      props: { question: sample, mode: "flat" }
    });
    expect(w.text()).toContain("Pick one");
    expect(w.text()).toContain("42 votes");
  });

  it("marks the option with highest count as leader", () => {
    const w = mount(ResultsPanel, {
      props: { question: sample, mode: "flat" }
    });
    const rows = w.findAll("[data-testid='rp-row']");
    expect(rows[1].attributes("data-leader")).toBeDefined();
    expect(rows[0].attributes("data-leader")).toBeUndefined();
  });

  it("shows empty state when total is 0", () => {
    const w = mount(ResultsPanel, {
      props: {
        question: { ...sample, tally: [] },
        mode: "flat"
      }
    });
    expect(w.text()).toContain("0 votes");
    const rows = w.findAll("[data-testid='rp-row']");
    rows.forEach(r => {
      expect(r.attributes("data-empty")).toBeDefined();
      expect(r.text()).toContain("—");
    });
  });

  it("computes percentages with tabular-nums", () => {
    const w = mount(ResultsPanel, {
      props: { question: sample, mode: "flat" }
    });
    const rows = w.findAll("[data-testid='rp-row']");
    expect(rows[1].text()).toContain("50%"); // 21/42
  });

  it("applies scrim mode data attribute", () => {
    const w = mount(ResultsPanel, {
      props: { question: sample, mode: "scrim-dark" }
    });
    expect(w.attributes("data-mode")).toBe("scrim-dark");
  });
});
