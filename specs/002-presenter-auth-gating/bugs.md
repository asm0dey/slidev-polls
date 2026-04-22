# Bug Reports: 002-presenter-auth-gating

## BUG-001

**Reported**: 2026-04-22
**Severity**: medium
**Status**: reported
**GitHub Issue**: _(none)_

**Description**: Deck auth control renders as floating overlay on the slide surface instead of integrating into the Slidev toolbar.

**Reproduction Steps**:
1. Run the slidev demo deck (`go-task slidev:demo` or equivalent `slidev` dev/build).
2. Observe the top-right of every slide — a password input labelled "deck token" floats on top of slide content (`frontends/slidev-component/global-top.vue` mounts `DeckAuthControl` via the `global-top` slot).
3. Expected: the auth control lives in Slidev's built-in toolbar / nav-controls area (e.g. via `custom-nav-controls.vue` or a toolbar slot), not painted onto the slide canvas.

**Root Cause**: _(empty until investigation)_

**Fix Reference**: _(empty until implementation)_
