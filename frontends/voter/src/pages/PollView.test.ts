import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@slidev-polls/shared", async () => {
  const actual = await vi.importActual<typeof import("@slidev-polls/shared")>("@slidev-polls/shared");
  return { ...actual, openPollStream: vi.fn(() => () => {}) };
});

import { flushPromises, mount } from "@vue/test-utils";
import PollView from "./PollView.vue";
import { ApiClient, ApiError } from "@slidev-polls/shared";
import type { PublicPollView, VoteAccepted } from "@slidev-polls/shared";

// Coverage for the voter PollView page. The ApiClient is injected via the `apiClient` prop so
// the tests can stub `publicPoll` / `submitVote` without monkey-patching the global fetch.
// Assertions follow the test-spec anchors from voter-flow.feature — the .feature file is a
// specification artefact (Principle VII), so the Gherkin scenario text is mirrored here as
// comments above the Vitest expectations.

function makeActiveView(): PublicPollView {
  return {
    pollId: "poll-uuid",
    slug: "my-talk",
    title: "My talk",
    state: "ACTIVE",
    style: {},
    activeQuestion: {
      id: "q1",
      prompt: "Which JVM?",
      ordinal: 0,
      status: "ACTIVE",
      options: [
        { id: "opt-a", label: "OpenJDK", position: 0 },
        { id: "opt-b", label: "GraalVM", position: 1 }
      ]
    }
  };
}

function makeWaitingView(): PublicPollView {
  return {
    pollId: "poll-uuid",
    slug: "my-talk",
    title: "My talk",
    state: "WAITING",
    style: {}
  };
}

function makeClient(overrides: {
  publicPoll?: (slug: string) => Promise<PublicPollView>;
  submitVote?: (slug: string, body: unknown) => Promise<VoteAccepted | null>;
} = {}): ApiClient {
  const accepted: VoteAccepted = { voteId: "vote-uuid", recordedAt: "2026-04-19T12:00:00Z" };
  return {
    publicPoll: vi.fn().mockResolvedValue(makeActiveView()),
    submitVote: vi.fn().mockResolvedValue(accepted),
    ...overrides
  } as unknown as ApiClient;
}

async function mountView(client: ApiClient, slug = "my-talk") {
  const wrapper = mount(PollView, { props: { slug, apiClient: client } });
  await flushPromises();
  return wrapper;
}

function wipeStorage() {
  for (let i = window.localStorage.length - 1; i >= 0; i--) {
    const key = window.localStorage.key(i);
    if (key) window.localStorage.removeItem(key);
  }
}

describe("PollView", () => {
  beforeEach(wipeStorage);

  // @TS-020 — anonymous visitor opens a poll with an ACTIVE question: the active question and
  // its options are rendered, the vote button is present for each option.
  it("renders the active question and its options once the fetch resolves", async () => {
    const client = makeClient();
    const wrapper = await mountView(client);

    expect(wrapper.find('[data-testid="poll-active"]').exists()).toBe(true);
    expect(wrapper.text()).toContain("Which JVM?");
    expect(wrapper.find('[data-testid="option-opt-a"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="option-opt-b"]').exists()).toBe(true);
  });

  // @TS-021 — anonymous visitor opens a poll with no active question: a friendly WAITING copy
  // renders instead of an error, and no options are visible.
  it("renders the WAITING state when no active question is returned", async () => {
    const client = makeClient({
      publicPoll: vi.fn().mockResolvedValue(makeWaitingView())
    });
    const wrapper = await mountView(client);

    expect(wrapper.find('[data-testid="poll-waiting"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="poll-active"]').exists()).toBe(false);
  });

  // @TS-022 — visitor submits a valid vote: submitVote is invoked with the clicked option, and
  // the view transitions to the confirmation state.
  it("submits the clicked option and transitions to the voted state", async () => {
    const submitVote = vi
      .fn()
      .mockResolvedValue({ voteId: "vote-uuid", recordedAt: "2026-04-19T12:00:00Z" });
    const client = makeClient({ submitVote });
    const wrapper = await mountView(client);

    await wrapper.find('[data-testid="option-opt-a"]').trigger("click");
    await wrapper.find('[data-testid="poll-submit"]').trigger("click");
    await flushPromises();

    expect(submitVote).toHaveBeenCalledWith(
      "my-talk",
      expect.objectContaining({ optionId: "opt-a" })
    );
    expect(wrapper.find('[data-testid="poll-voted"]').exists()).toBe(true);
    // The alreadyVoted cache is now populated so a reload would skip the active UI.
    expect(window.localStorage.getItem("slidev-polls:already-voted:my-talk:q1")).toBe("1");
  });

  // @TS-023 — duplicate submission surfaces as ALREADY_VOTED from the server; the PollView
  // treats it as "already done" rather than showing a generic error. Either a duplicate tab or
  // a stale client state can trigger this path — the UX should be the same.
  it("treats ALREADY_VOTED responses as the voted state", async () => {
    const submitVote = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          409,
          { code: "ALREADY_VOTED", message: "already voted" },
          "already voted"
        )
      );
    const client = makeClient({ submitVote });
    const wrapper = await mountView(client);

    await wrapper.find('[data-testid="option-opt-a"]').trigger("click");
    await wrapper.find('[data-testid="poll-submit"]').trigger("click");
    await flushPromises();

    expect(wrapper.find('[data-testid="poll-voted"]').exists()).toBe(true);
    expect(window.localStorage.getItem("slidev-polls:already-voted:my-talk:q1")).toBe("1");
  });

  // @TS-025 — QUESTION_NOT_ACTIVE is Principle IV territory: the presenter closed the question
  // between the render and the submit. The page must surface a non-technical message AND
  // refresh itself so the audience sees whatever's next (typically WAITING for another
  // question, or a different ACTIVE one). A plain "error" toast would strand them.
  it("refreshes the poll when QUESTION_NOT_ACTIVE fires during submit", async () => {
    const publicPoll = vi
      .fn()
      // First load returns the active question; after the close, a second load returns WAITING.
      .mockResolvedValueOnce(makeActiveView())
      .mockResolvedValueOnce(makeWaitingView());
    const submitVote = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          409,
          { code: "QUESTION_NOT_ACTIVE", message: "closed" },
          "closed"
        )
      );
    const client = makeClient({ publicPoll, submitVote });
    const wrapper = await mountView(client);

    await wrapper.find('[data-testid="option-opt-a"]').trigger("click");
    await wrapper.find('[data-testid="poll-submit"]').trigger("click");
    await flushPromises();

    // After the refetch, the WAITING state renders — no duplicate-vote error message sticks.
    expect(wrapper.find('[data-testid="poll-waiting"]').exists()).toBe(true);
    expect(publicPoll).toHaveBeenCalledTimes(2);
  });

  // Cache-path: a client that already voted on this slug in a prior reload keeps the confirmed
  // UX on the next visit, even before the alreadyVoted hint lands in the response. This is the
  // offline-UX argument for keeping a localStorage flag alongside the server cookie.
  it("shows the voted state when the localStorage cache says so", async () => {
    window.localStorage.setItem("slidev-polls:already-voted:my-talk:q1", "1");
    const client = makeClient();
    const wrapper = await mountView(client);

    expect(wrapper.find('[data-testid="poll-voted"]').exists()).toBe(true);
  });

  // When the server reports a 404 for the slug (typo, deleted poll), the error state renders
  // actionable copy — Principle VI: no generic "something went wrong".
  it("renders an actionable error message when the slug does not resolve", async () => {
    const client = makeClient({
      publicPoll: vi
        .fn()
        .mockRejectedValue(
          new ApiError(404, { code: "NOT_FOUND", message: "no such poll" }, "no such poll")
        )
    });
    const wrapper = await mountView(client, "typoed");

    const err = wrapper.find('[data-testid="poll-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text().toLowerCase()).not.toContain("something went wrong");
    expect(err.text().toLowerCase()).toContain("double-check");
  });
});
