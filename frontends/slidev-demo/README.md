# Slidev demo deck

Hand-runnable Slidev deck that exercises feature 002 (presenter-auth-gating)
end-to-end: in-deck auth control, deck-token activation on navigation,
anonymous read-only view, revocation reaction.

## Prereqs

- Backend running at `http://localhost:8080`. Easiest: `task up:detached`
  from the repo root. First-run setup: open
  `http://localhost:8080/admin/` and create the initial presenter
  account.
- `bun install` run at the repo root.

## Steps

```bash
cd frontends/slidev-demo
bun install      # first time only — pulls @slidev/cli into this workspace
bun run slidev   # opens http://localhost:3030
```

Or from the repo root: `task slidev:demo`.

You provision the demo poll yourself via the backoffice:

1. Sign in at `http://localhost:8080/admin/` and create a poll with the
   questions you want to demo (allow `http://localhost:3030` as an origin so
   the deck can reach the backend cross-origin).
2. On each question, click **Copy snippet** — the backoffice mints a fresh
   deck token and writes a fully-populated
   `<PollResults slug pollId questionId deckToken />` to your clipboard.
   Paste those tags into `slides.md` on the slides where you want the live
   results to render.
3. Reload the deck. Slides 5 / 6 host the pasted `<PollResults>` for Q1 / Q2.
   Slides 3 / 4 show join QR codes via `<PollQr slug="…" />` (a centered one
   and a `layout: two-cols` variant) — audience scans to reach
   `http://localhost:8080/{slug}` without signing in.
4. The auth control top-right of the deck signs the deck in via the deck
   token; navigating to a slide that owns a question fires one
   `POST /api/deck/polls/{pollId}/activate`.
5. Open a **second browser profile** pointed at the same deck URL. Without
   signing in, navigate across the same slides — zero activation POSTs
   originate from that profile. Live tallies still render.
6. Vote from any browser at `http://localhost:8080/{slug}`. Tallies on the
   slide update within ~2 s.
7. Revoke the token from the backoffice (`DELETE
/api/admin/polls/{pollId}/deck-tokens/{tokenId}`, session-authenticated).
   Navigate to another poll slide in the signed-in browser — the auth
   control reverts to _not signed in_ with _credential not recognised_;
   the poll's active question is unchanged.

## Files

| File           | Role                                                    |
| -------------- | ------------------------------------------------------- |
| `slides.md`    | Deck markdown with hardcoded slug / pollId / questions. |
| `package.json` | Workspace member; depends on `@slidev-polls/component`. |
