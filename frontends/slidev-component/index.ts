import "@slidev-polls/shared/tokens.css";

export { default as PollResults } from "./components/PollResults.vue";
export { configureDeckAuthBackend } from "./composables/configureDeckAuthBackend";
export {
  usePollResults,
  usePollResultsMap,
  setPollResults,
  clearPollResults
} from "./composables/usePollResults";
export type { SnapshotEvent, TallyEntry } from "@slidev-polls/shared";
