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
