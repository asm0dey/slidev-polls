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
  options: OptionLite[];
  tally: TallyLite[];
}

const props = withDefaults(
  defineProps<{
    question: QuestionLite;
    mode?: "flat" | "scrim-dark" | "scrim-light";
    showLive?: boolean;
  }>(),
  { mode: "flat", showLive: true }
);

const total = computed(() => props.question.tally.reduce((s, t) => s + t.count, 0));

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

function pctOf(id: string): number {
  if (total.value === 0) return 0;
  return Math.round((countOf(id) / total.value) * 100);
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
.sp-rp__row[data-leader] {
  color: var(--sp-accent-fg);
}
.sp-rp[data-mode="scrim-dark"] .sp-rp__row[data-leader] {
  color: #fff;
}
.sp-rp[data-mode="scrim-light"] .sp-rp__row[data-leader] {
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
</style>
