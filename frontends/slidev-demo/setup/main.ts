// Side-effect import: data.ts calls configureDeckAuthBackend(pollServer) on
// module load, which the in-deck auth control needs *before* the singleton in
// useDeckAuth() rehydrates from localStorage. If we waited for slide 3 (the
// first slide that imports data) to load, a reload on slide 1 would fire the
// rehydrate verify against the slidev dev server (same-origin, :3030) and
// flip the deck back to "not signed in".
import "../data";

// Slidev runs ./setup/main.ts during app boot before any slide mounts. The
// default export is the per-app Vue setup hook; we have no plugins to wire,
// so this is a no-op — the work is the side-effect import above.
export default function () {}
