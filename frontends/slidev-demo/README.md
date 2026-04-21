# Slidev demo deck

Hand-runnable Slidev deck that exercises feature 002 (presenter-auth-gating)
end-to-end: in-deck auth control, deck-token activation on navigation,
anonymous read-only view, revocation reaction.

## Prereqs

- Backend running at `http://localhost:8080`. Easiest: `task up:detached`
  from the repo root. The dev profile seeds admin `alice` /
  `correct-horse`.
- `bun install` run at the repo root.

## Steps

```bash
cd frontends/slidev-demo
bun install      # first time only — pulls @slidev/cli into this workspace
bun run seed     # creates a poll, mints a deck token, rewrites data.ts
bun run slidev   # opens http://localhost:3030
```

Or from the repo root: `task slidev:demo` (seeds and launches in one go).

1. Slide 3 / 4 host `<PollResults>` for Q1 / Q2.
2. Paste the **deck token** printed by `seed` into the control in the
   top-right corner of the deck. It flips to *signed in: demo-deck*.
3. Navigate to slide 3 while signed in — network panel shows one
   `POST /api/deck/polls/{pollId}/activate` with `X-Deck-Token`.
4. Open a **second browser profile** pointed at the same deck URL. Without
   signing in, navigate across the same slides — zero activation POSTs
   originate from that profile. Live tallies still render.
5. Vote from any browser at `http://localhost:8080/{slug}` (printed by
   `seed`). Tallies on the slide update within ~2 s.
6. Revoke the token from the backoffice (`DELETE
   /api/admin/polls/{pollId}/deck-tokens/{tokenId}`, session-authenticated).
   Navigate to another poll slide in the signed-in browser — the auth
   control reverts to *not signed in* with *credential not recognised*;
   the poll's active question is unchanged.

## Files

| File              | Role                                                    |
|-------------------|---------------------------------------------------------|
| `slides.md`       | Deck markdown; imports poll config from `./data.ts`.    |
| `data.ts`         | Generated config stub. `scripts/seed.sh` rewrites it.   |
| `scripts/seed.sh` | Mints the poll + deck token via the backend admin API.  |
| `package.json`    | Workspace member; depends on `@polls/slidev-addon`.     |
