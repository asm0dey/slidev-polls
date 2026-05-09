// Read pollServer from the deck headmatter (slides.md first frontmatter
// block) and route the in-deck auth control + SSE + activate calls at it.
// Slidev runs setup/main.ts during app boot before any slide mounts, so the
// configureDeckAuthBackend() call lands before useDeckAuth() rehydrates from
// localStorage. Without this, a reload on slide 1 would verify against the
// slidev dev server (same-origin, :3030) and flip the deck back to
// "not signed in".
import { configs } from "@slidev/client/env";
import { configureDeckAuthBackend } from "@polls/slidev-addon";

const pollServer = (configs as Record<string, unknown>).pollServer;
if (typeof pollServer === "string" && pollServer.length > 0) {
  configureDeckAuthBackend(pollServer);
}

export default function () {}
