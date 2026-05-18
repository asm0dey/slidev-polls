<script setup lang="ts">
import { computed } from "vue";
import LiveDot from "./LiveDot.vue";

interface OptionLite {
  id: string;
  label: string;
}
interface TallyLite {
  optionId: string;
  count: number;
}
interface QuestionLite {
  prompt: string;
  /** Per-question arity. Optional so callers that only have legacy single-choice fixtures still
   *  type-check; missing/undefined is treated as single-choice (1, 1) and suppresses the
   *  voters/selections footer. */
  minSelections?: number;
  maxSelections?: number;
  options: OptionLite[];
  tally: TallyLite[];
}

const props = withDefaults(
  defineProps<{
    question: QuestionLite;
    mode?: "flat" | "scrim-dark" | "scrim-light";
    showLive?: boolean;
    /** Ballots-cast figure from the snapshot. Drives the "{voterCount} voters · {selections}
     *  selections" footer when {@link QuestionLite.maxSelections} is > 1. Defaults to 0 so legacy
     *  single-choice callers don't need to thread it. */
    voterCount?: number;
  }>(),
  { mode: "flat", showLive: true, voterCount: 0 }
);

const total = computed(() => props.question.tally.reduce((s, t) => s + t.count, 0));
// A question is "multi-choice" when the panel can show more selections than voters. Single-choice
// questions (the legacy default) hide the footer entirely — voters == selections by construction
// so the extra line is redundant noise.
const isMulti = computed(() => (props.question.maxSelections ?? 1) > 1);

const leaderId = computed(() => {
  if (total.value === 0) return null;
  let best: { id: string; count: number } | null = null;
  let tied = false;
  for (const o of props.question.options) {
    const c = props.question.tally.find((t) => t.optionId === o.id)?.count ?? 0;
    if (!best || c > best.count) {
      best = { id: o.id, count: c };
      tied = false;
    } else if (c === best.count) {
      tied = true;
    }
  }
  // No leader on a tie — the bright accent on a single bar would imply that
  // option is winning when it actually isn't.
  if (tied) return null;
  return best?.id ?? null;
});

function countOf(id: string): number {
  return props.question.tally.find((t) => t.optionId === id)?.count ?? 0;
}

// Multi-choice questions report "share of voters who picked this option" — each voter contributes
// at most 1 to a given option's count, so count/voterCount answers the natural "what fraction of
// the room picked X?". Using total selections (the legacy denominator) made each row's % depend
// on how many *other* options were also picked, which read as "33% of 2 voters" nonsense in the
// presenter view. Single-choice keeps the historical count/total denominator (identical when
// voterCount === total).
const denominator = computed(() =>
  isMulti.value && props.voterCount > 0 ? props.voterCount : total.value
);

function pctOf(id: string): number {
  const d = denominator.value;
  if (d === 0) return 0;
  return Math.round((countOf(id) / d) * 100);
}
</script>

<template>
  <section class="sp-rp" :data-mode="mode" data-testid="results-panel">
    <header class="sp-rp__head">
      <h3 class="sp-rp__prompt">{{ question.prompt }}</h3>
      <div class="sp-rp__meta">
        <LiveDot v-if="showLive" />
        <span>{{ total }} {{ total === 1 ? "vote" : "votes" }}</span>
      </div>
    </header>
    <ol class="sp-rp__rows" :aria-live="showLive ? 'polite' : 'off'">
      <li
        v-for="opt in question.options"
        :key="opt.id"
        class="sp-rp__row"
        :data-leader="leaderId === opt.id ? '' : undefined"
        :data-empty="total === 0 ? '' : undefined"
        data-testid="rp-row"
        :data-option-id="opt.id"
      >
        <span class="sp-rp__fill" :style="{ width: pctOf(opt.id) + '%' }" />
        <span class="sp-rp__label">{{ opt.label }}</span>
        <span class="sp-rp__count" aria-hidden="true">{{ countOf(opt.id) }}</span>
        <span class="sp-rp__pct">{{ total === 0 ? "—" : pctOf(opt.id) + "%" }}</span>
      </li>
    </ol>
    <p v-if="isMulti" class="sp-rp__footer" data-testid="results-footer">
      {{ voterCount }} {{ voterCount === 1 ? "voter" : "voters" }} · {{ total }}
      {{ total === 1 ? "selection" : "selections" }}
    </p>
  </section>
</template>

<style scoped>
.sp-rp {
  font-family: var(--sp-font-sans);
  background: var(--sp-bg);
  color: var(--sp-fg);
  border-radius: var(--sp-radius-xl);
  padding: 24px 28px;
  width: 100%;
  box-sizing: border-box;
}
.sp-rp[data-mode="scrim-dark"] {
  background: rgba(10, 10, 10, 0.55);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: #fafafa;
}
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .sp-rp[data-mode="scrim-dark"] {
    background: rgba(10, 10, 10, 0.78);
    box-shadow: 0 24px 48px -12px rgba(0, 0, 0, 0.5);
  }
}
.sp-rp[data-mode="scrim-light"] {
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(14px) saturate(140%);
  -webkit-backdrop-filter: blur(14px) saturate(140%);
  border: 1px solid rgba(255, 255, 255, 0.5);
  color: #0a0a0a;
}

.sp-rp__head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 14px;
}
.sp-rp__prompt {
  font-size: 18px;
  font-weight: 600;
  letter-spacing: -0.01em;
  margin: 0;
}
.sp-rp__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: inherit;
  opacity: 0.75;
}

.sp-rp__rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.sp-rp__row {
  position: relative;
  overflow: hidden;
  border-radius: var(--sp-radius-lg);
  background: var(--sp-bg-subtle);
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  padding: 12px 16px;
  font-size: 14px;
}
.sp-rp[data-mode="scrim-dark"] .sp-rp__row {
  background: rgba(255, 255, 255, 0.08);
}
.sp-rp[data-mode="scrim-light"] .sp-rp__row {
  background: rgba(0, 0, 0, 0.06);
}

.sp-rp__row[data-empty] {
  background: transparent;
  border: 1px solid var(--sp-border);
}
.sp-rp[data-mode="scrim-dark"] .sp-rp__row[data-empty] {
  border-color: rgba(255, 255, 255, 0.16);
}

.sp-rp__fill {
  position: absolute;
  inset: 0;
  width: 0;
  background: var(--sp-accent-soft);
  transition: width var(--sp-dur) var(--sp-ease);
}
.sp-rp[data-mode="scrim-dark"] .sp-rp__fill {
  background: rgba(255, 255, 255, 0.18);
}
.sp-rp[data-mode="scrim-light"] .sp-rp__fill {
  background: rgba(0, 0, 0, 0.12);
}

.sp-rp__row[data-leader] .sp-rp__fill {
  background: var(--sp-accent);
}
/* Leader text colour is applied per-element, not row-wide. The bar fill only
   covers `pct%` of the row width, so a row-level color override painted the
   percentage label (right-anchored, outside the bar) in the on-accent fg —
   e.g. near-black "33%" sitting on the dark row background in dark theme,
   which read as illegible. Label sits at the left edge (always inside the
   bar when pct > 0) so it gets the on-accent fg; the pct stays in theme fg
   so it remains readable against `--sp-bg-subtle` outside the bar. */
.sp-rp__row[data-leader] .sp-rp__label {
  color: var(--sp-accent-fg);
}
.sp-rp[data-mode="scrim-dark"] .sp-rp__row[data-leader] .sp-rp__label {
  color: #fff;
}
.sp-rp[data-mode="scrim-light"] .sp-rp__row[data-leader] .sp-rp__label {
  color: #fff;
}
.sp-rp__row[data-leader] .sp-rp__label,
.sp-rp__row[data-leader] .sp-rp__pct {
  font-weight: 600;
}

.sp-rp__label,
.sp-rp__pct,
.sp-rp__count {
  position: relative;
}
.sp-rp__pct {
  font-variant-numeric: tabular-nums;
}
.sp-rp__count {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.sp-rp__footer {
  margin: 12px 0 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  opacity: 0.75;
  letter-spacing: 0.01em;
}
</style>
