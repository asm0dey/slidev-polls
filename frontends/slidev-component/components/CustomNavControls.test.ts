import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick, ref } from "vue";
import { mount } from "@vue/test-utils";
import type { DeckAuthState, DeckAuthStatus, UseDeckAuthReturn } from "../composables/useDeckAuth";
import CustomNavControls from "./CustomNavControls.vue";

// Tests mirror bugfix_BUG-001.feature — auth control rendered inside Slidev's nav slot,
// not on slide canvas. Given/When/Then echoed as comments per Principle VII.

type Overrides = Partial<{
  status: DeckAuthStatus;
  state: Partial<DeckAuthState>;
  message: string | null;
}>;

function makeStub(overrides: Overrides = {}): UseDeckAuthReturn & {
  _calls: { signIn: string[]; signOut: number; markRevoked: number };
} {
  const status = ref<DeckAuthStatus>(overrides.status ?? "anonymous");
  const state = ref<DeckAuthState>({
    token: null,
    tokenId: null,
    pollId: null,
    label: null,
    verifiedAt: null,
    ...(overrides.state ?? {})
  });
  const message = ref<string | null>(overrides.message ?? null);
  const _calls = { signIn: [] as string[], signOut: 0, markRevoked: 0 };
  return {
    status,
    state,
    message,
    signIn: async (token: string) => {
      _calls.signIn.push(token);
    },
    signInWithCredentials: async (_u: string, _p: string) => {
      // CustomNavControls does not call this; included to satisfy UseDeckAuthReturn.
    },
    signOut: () => {
      _calls.signOut++;
      status.value = "anonymous";
    },
    markRevoked: () => {
      _calls.markRevoked++;
      status.value = "revoked";
    },
    _calls
  };
}

let stub: ReturnType<typeof makeStub>;

vi.mock("../composables/useDeckAuth", () => ({
  useDeckAuth: () => stub
}));

describe("CustomNavControls", () => {
  beforeEach(() => {
    stub = makeStub();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // @BUG-001 — Given anonymous, Then a "sign in" trigger renders in the toolbar and no token
  // input is painted on the slide canvas (the input only lives inside the popover, which is
  // closed by default).
  it("BUG-001: anonymous default renders trigger labelled 'sign in' with popover closed (no token input in DOM)", () => {
    stub = makeStub({ status: "anonymous" });
    const wrapper = mount(CustomNavControls);
    const trigger = wrapper.get("[data-testid='deck-auth-nav-trigger']");
    expect(trigger.text().toLowerCase()).toContain("sign in");
    expect(wrapper.find("[data-testid='deck-auth-nav-popover']").exists()).toBe(false);
    expect(wrapper.find("input").exists()).toBe(false);
  });

  // @BUG-001 — When presenter clicks the trigger, Then the popover opens and exposes the
  // existing DeckAuthControl form (input + submit).
  it("BUG-001: clicking the trigger opens a popover containing the token input", async () => {
    stub = makeStub({ status: "anonymous" });
    const wrapper = mount(CustomNavControls);
    await wrapper.get("[data-testid='deck-auth-nav-trigger']").trigger("click");
    const popover = wrapper.get("[data-testid='deck-auth-nav-popover']");
    expect(popover.find("input").exists()).toBe(true);
    expect(popover.find("button[type='submit']").exists()).toBe(true);
  });

  // @BUG-001 — Given signed-in with label "Laptop", Then the toolbar trigger shows the label
  // pill so the presenter can see the signed-in state without opening the popover.
  it("BUG-001: signed-in trigger surfaces the label pill in the nav slot", () => {
    stub = makeStub({
      status: "signed-in",
      state: { label: "Laptop", token: "dtk-1" }
    });
    const wrapper = mount(CustomNavControls);
    const trigger = wrapper.get("[data-testid='deck-auth-nav-trigger']");
    expect(trigger.text()).toContain("Laptop");
  });

  // @BUG-001 — When trigger clicked again while the popover is open, Then the popover closes
  // so repeated clicks toggle the control (keeps the slide canvas clean between uses).
  it("BUG-001: trigger toggles the popover open/closed", async () => {
    stub = makeStub({ status: "anonymous" });
    const wrapper = mount(CustomNavControls);
    const trigger = wrapper.get("[data-testid='deck-auth-nav-trigger']");
    await trigger.trigger("click");
    expect(wrapper.find("[data-testid='deck-auth-nav-popover']").exists()).toBe(true);
    await trigger.trigger("click");
    expect(wrapper.find("[data-testid='deck-auth-nav-popover']").exists()).toBe(false);
  });

  // @BUG-001 — Given signed-in, When status flips to "revoked" (session revoked mid-talk),
  // Then the trigger returns to its "sign in" label so the presenter sees the state change
  // without having to open the popover.
  it("BUG-001: trigger label reflects status transitions (signed-in → revoked)", async () => {
    stub = makeStub({
      status: "signed-in",
      state: { label: "Laptop", token: "dtk-1" }
    });
    const wrapper = mount(CustomNavControls);
    expect(wrapper.get("[data-testid='deck-auth-nav-trigger']").text()).toContain("Laptop");
    stub.status.value = "revoked";
    await nextTick();
    expect(wrapper.get("[data-testid='deck-auth-nav-trigger']").text().toLowerCase()).toContain(
      "sign in"
    );
  });
});
