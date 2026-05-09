import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { nextTick, ref } from "vue";
import { flushPromises, mount } from "@vue/test-utils";
import type {
  DeckAuthState,
  DeckAuthStatus,
  UseDeckAuthReturn
} from "../composables/useDeckAuth";
import DeckAuthControl from "./DeckAuthControl.vue";

// Tests mirror deck-auth-signin.feature — Gherkin G/W/T echoed as comments per Principle VII.

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

describe("DeckAuthControl", () => {
  beforeEach(() => {
    stub = makeStub();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // @TS-100 — Given no prior auth state, When the control renders, Then the visible state
  // reads "not signed in" and a sign-in form is exposed.
  it("TS-100: anonymous render shows 'not signed in' with a form", () => {
    stub = makeStub({ status: "anonymous" });
    const wrapper = mount(DeckAuthControl);
    expect(wrapper.text()).toMatch(/not signed in/i);
    expect(wrapper.find("input").exists()).toBe(true);
    expect(wrapper.find("button[type='submit']").exists()).toBe(true);
  });

  // @TS-101 — Given signed-in with label "Laptop", Then the pill carries the label and a
  // sign-out affordance is visible.
  it("TS-101: signed-in render shows label pill and sign-out", () => {
    stub = makeStub({
      status: "signed-in",
      state: { label: "Laptop", token: "dtk-1" }
    });
    const wrapper = mount(DeckAuthControl);
    expect(wrapper.text()).toContain("Laptop");
    expect(wrapper.find("[data-testid='deck-auth-signout']").exists()).toBe(true);
  });

  // @TS-104 — When sign-out affordance activated, Then composable signOut() invoked exactly once.
  it("TS-104: sign-out button invokes composable signOut() once", async () => {
    stub = makeStub({
      status: "signed-in",
      state: { label: "Laptop", token: "dtk-1" }
    });
    const wrapper = mount(DeckAuthControl);
    await wrapper.get("[data-testid='deck-auth-signout']").trigger("click");
    expect(stub._calls.signOut).toBe(1);
  });

  // @TS-105 / @TS-106 — When message ref is set to each distinct string, Then the component
  // renders exactly the string it was given (no collapse of the two).
  it("TS-105 / TS-106: distinct auth-failure vs transport messages render verbatim", async () => {
    stub = makeStub({ status: "anonymous", message: "credential not recognised" });
    const wrapper = mount(DeckAuthControl);
    expect(wrapper.text()).toContain("credential not recognised");

    stub.message.value = "couldn't reach server";
    await nextTick();
    expect(wrapper.text()).toContain("couldn't reach server");
    expect(wrapper.text()).not.toContain("credential not recognised");
  });

  // @TS-102 — Given composable status flips from anonymous → signed-in after the control was
  // mounted, When the user re-renders (slide navigation is simulated by remount + shared stub),
  // Then the component re-reads composable state without re-prompting.
  it("TS-102: remount re-reads composable state without holding hidden local state", async () => {
    stub = makeStub({
      status: "signed-in",
      state: { label: "Laptop", token: "dtk-1" }
    });
    const first = mount(DeckAuthControl);
    expect(first.text()).toContain("Laptop");
    first.unmount();

    // state flips while no component is mounted
    stub.status.value = "anonymous";
    stub.state.value = {
      token: null,
      tokenId: null,
      pollId: null,
      label: null,
      verifiedAt: null
    };

    const second = mount(DeckAuthControl);
    await flushPromises();
    expect(second.text()).toMatch(/not signed in/i);
    expect(second.text()).not.toContain("Laptop");
  });
});
