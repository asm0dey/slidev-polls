# Bug Reports: 002-presenter-auth-gating

## BUG-001

**Reported**: 2026-04-22
**Severity**: medium
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Deck auth control renders as floating overlay on the slide surface instead of integrating into the Slidev toolbar.

**Reproduction Steps**:
1. Run the slidev demo deck (`go-task slidev:demo` or equivalent `slidev` dev/build).
2. Observe the top-right of every slide — a password input labelled "deck token" floats on top of slide content (`frontends/slidev-component/global-top.vue` mounts `DeckAuthControl` via the `global-top` slot).
3. Expected: the auth control lives in Slidev's built-in toolbar / nav-controls area (e.g. via `custom-nav-controls.vue` or a toolbar slot), not painted onto the slide canvas.

**Root Cause**: The addon hosted `DeckAuthControl` inside `global-top.vue`, which Slidev auto-mounts as an overlay layer above every slide. The control's own stylesheet pinned it with `position: fixed; top/right` — painting the password input directly on the slide canvas. Slidev exposes a dedicated toolbar-integration slot (`custom-nav-controls.vue`, per sli.dev/features/global-layers) that was not used.

**Fix Reference**: T-B001 / T-B002. New `frontends/slidev-component/custom-nav-controls.vue` (auto-picked by Slidev into the nav bar) wraps `components/CustomNavControls.vue`, which renders a single toolbar button ("sign in" when anonymous, label pill when signed-in) that toggles a popover containing the existing `DeckAuthControl`. `global-top.vue` deleted; `DeckAuthControl.vue` stripped of fixed-positioning styles so it flows inside the popover.

---

## BUG-002

**Reported**: 2026-04-22
**Severity**: high
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Deck auth uses a single-token paste field instead of a login+password form matching the admin UI. Spec intent is parity with admin UI authentication (username + password), not opaque-token entry.

**Reproduction Steps**:
1. Run the slidev demo deck (`go-task slidev:demo`).
2. Click the "sign in" button in the Slidev nav bar to open the auth popover.
3. Observe: popover shows a single `type="password"` input labelled "deck token" (`frontends/slidev-component/components/DeckAuthControl.vue`).
4. Expected: popover shows a login (username) field and a password field, submitting against the same credential flow used by the admin UI login page.

**Root Cause**: The deck-side auth UI predated the admin UI parity requirement and accepted a presenter-supplied bearer string directly. There was no backend login endpoint for deck use — only `GET /api/deck/auth/me` consuming an already-minted token — so the addon had nowhere to send `{username, password}`. Sign-in remained an opaque token-paste flow.

**Fix Reference**: T-B003 / T-B004. Backend adds `POST /api/deck/auth/login` (`DeckAuthController.login`) that authenticates via the shared `AuthenticationManager` (parity with admin UI), picks the presenter's most-recent poll, mints a fresh deck token via `DeckTokenService.mint(...)`, and returns `{token, tokenId, pollId, label}`. Front-end `DeckAuthControl.vue` replaces the single token input with login + password fields; `useDeckAuth.signInWithCredentials(username, password)` POSTs to the new endpoint and persists the minted token under the existing `slidev-polls:deck-auth` localStorage key.

---

## BUG-003

**Reported**: 2026-04-22
**Severity**: high
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: Deck auth popover is clipped below the viewport and unreachable. Slidev's `custom-nav-controls` slot sits at the bottom of the window; the popover opens downward from the trigger and so renders outside the visible area.

**Reproduction Steps**:
1. Run the slidev demo deck (`go-task slidev:demo`).
2. Click the "sign in" button in the Slidev nav bar.
3. Observe: popover content is hidden below the viewport edge — `frontends/slidev-component/components/CustomNavControls.vue` uses `position: absolute; top: calc(100% + 0.35rem)` which places the popover under the nav bar (and under the screen bottom).
4. Expected: popover opens upward from the trigger (e.g. `bottom: calc(100% + …)`) so it is fully visible above the nav bar regardless of viewport height.

**Root Cause**: `.deck-auth-nav__popover` was authored with a top-anchored offset (`top: calc(100% + 0.35rem)`) inherited from the BUG-001 nav-controls migration. That assumed the trigger sat at the top of its container. Slidev's `custom-nav-controls` slot is at the bottom of the viewport, so a top-anchored popover opens downward off-screen.

**Fix Reference**: T-B005 / T-B006. Anchor swapped to `bottom: calc(100% + 0.35rem)` in `frontends/slidev-component/components/CustomNavControls.vue` so the popover opens upward from the trigger and stays inside the viewport.

---

## BUG-004

**Reported**: 2026-04-22
**Severity**: medium
**Status**: fixed
**GitHub Issue**: _(none)_

**Description**: A table-of-contents-style list is rendered on top of the slide content in the slidev demo deck. No addon component should be painting a TOC on the slide canvas after BUG-001 removed `global-top.vue`; exact source is unknown and needs investigation (candidates: Slidev built-in side nav/drawer being shown by default, an auto-mounted slot like `global-top.vue` / `global-bottom.vue` still in the project, or a stray component leaking from the addon).

**Reproduction Steps**:
1. Run the slidev demo deck (`go-task slidev:demo`).
2. Navigate to the first slide (SPA mode and/or presenter mode).
3. Observe: a TOC-style list of slide titles/headings is visible overlaid on top of the slide content — it should not be there.
4. Expected: no TOC overlay on the slide canvas. The slide renders only the authored content; any Slidev TOC remains collapsed behind its toggle unless explicitly opened.

**Root Cause**: Bug in upstream Slidev 0.49.29's `internals/Goto.vue` (the `g`-key "goto slide" dialog). Its result list was computed as `fuse.search(path)` regardless of input — and Fuse with an empty query returns every slide, so `result.length > 0` was always true. The dialog container is hidden when closed by translating it to `top: -80px` (only ~40px tall input fits in that hidden band), but the autocomplete-list is a flow-layout sibling that overflows downward into the viewport — painting all slide titles on top of the slide canvas. Investigation 2026-05-09 (DOM capture from live `go-task slidev:demo`) confirmed `#slidev-goto-dialog .autocomplete-list` was the offending element. Slidev 52.15.1 fixes the source: `result` returns `[]` when `path` is empty, so the list never renders while the dialog is closed.

**Fix Reference**: T-B007 / T-B008. Bump `@slidev/cli` from `^0.49.0` to `^52.15.1` in `frontends/slidev-demo/package.json`. `@slidev/theme-default ^0.25.0` is unchanged (still latest); `custom-nav-controls.vue` slot remains the addon mount point in v52, so no addon-side change is required. Verified visually in both SPA (`http://localhost:3030/1`) and presenter (`http://localhost:3030/presenter/1`) modes that no TOC overlay paints on the slide canvas, and that `#slidev-goto-dialog`'s `.autocomplete-list` is no longer present in the DOM until the goto dialog is explicitly opened.
