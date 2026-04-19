import type { SnapshotEvent, TallyDeltaEvent } from "./types";

export interface StreamHandlers {
  onSnapshot: (ev: SnapshotEvent) => void;
  onTally:    (ev: TallyDeltaEvent) => void;
  onQuestionClosed?: (ev: { pollId: string; questionId: string }) => void;
  onConnectionStateChange?: (state: "open" | "paused") => void;
}

export function openPollStream(baseUrl: string, slug: string, h: StreamHandlers): () => void {
  const url = `${baseUrl.replace(/\/$/, "")}/api/polls/${encodeURIComponent(slug)}/stream`;
  const es = new EventSource(url, { withCredentials: true });

  es.addEventListener("open", () => h.onConnectionStateChange?.("open"));
  es.addEventListener("error", () => h.onConnectionStateChange?.("paused"));

  es.addEventListener("snapshot", (e) => {
    h.onSnapshot(JSON.parse((e as MessageEvent).data) as SnapshotEvent);
  });
  es.addEventListener("tally", (e) => {
    h.onTally(JSON.parse((e as MessageEvent).data) as TallyDeltaEvent);
  });
  es.addEventListener("question-closed", (e) => {
    h.onQuestionClosed?.(JSON.parse((e as MessageEvent).data));
  });

  return () => es.close();
}
