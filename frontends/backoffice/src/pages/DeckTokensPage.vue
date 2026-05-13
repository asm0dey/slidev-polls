<template>
  <section class="dt" data-testid="deck-tokens-page">
    <header class="dt__head">
      <div>
        <h1 class="dt__title">Deck tokens</h1>
        <p class="dt__sub">
          Tokens authorise a Slidev deck to push poll state. They are minted automatically when you
          sign into the deck.
          <router-link :to="{ name: 'poll-edit', params: { pollId } }" class="dt__back">
            ← back to poll
          </router-link>
        </p>
      </div>
    </header>

    <p v-if="errorMessage" class="dt__error" role="alert" data-testid="deck-tokens-error">
      {{ errorMessage }}
    </p>

    <div v-if="loading" data-testid="loading">Loading tokens…</div>
    <template v-else>
      <p v-if="tokens.length === 0" class="dt__empty" data-testid="empty">
        No deck tokens yet. Sign in from your deck to create one.
      </p>

      <div v-else class="dt__table" data-testid="tokens-table">
        <div class="dt__row dt__row--head">
          <span>Label</span><span>Token</span><span>Created</span><span>Status</span><span></span>
        </div>
        <div
          v-for="token in tokens"
          :key="token.id"
          class="dt__row"
          :data-testid="`row-${token.id}`"
        >
          <span>{{ token.label ?? "(unlabeled)" }}</span>
          <code class="dt__code">{{ maskedToken(token) }}</code>
          <span class="dt__when">{{ formatRelative(token.createdAt) }}</span>
          <span>
            <span v-if="token.revokedAt" class="dt__status dt__status--revoked">
              Revoked {{ formatRelative(token.revokedAt) }}
            </span>
            <span v-else class="dt__status dt__status--live">Live</span>
          </span>
          <Button
            v-if="!token.revokedAt"
            variant="ghost"
            size="sm"
            :disabled="revokingId === token.id"
            :data-testid="`revoke-${token.id}`"
            @click="onRevoke(token.id)"
          >
            {{ revokingId === token.id ? "Revoking…" : "Revoke" }}
          </Button>
        </div>
      </div>
    </template>
  </section>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted, type PropType } from "vue";
import type { DeckToken } from "@slidev-polls/shared";
import { AdminApiClient, AdminApiError, defaultAdminClient } from "../lib/admin-api";
import { Button } from "@slidev-polls/shared/ui";

export default defineComponent({
  name: "DeckTokensPage",
  components: { Button },
  props: {
    pollId: { type: String, required: true },
    apiClient: { type: Object as PropType<AdminApiClient>, default: null }
  },
  setup(props) {
    const client = (props.apiClient ?? defaultAdminClient) as AdminApiClient;
    const tokens = ref<DeckToken[]>([]);
    const loading = ref(true);
    const revokingId = ref<string | null>(null);
    const errorMessage = ref<string | null>(null);

    async function refresh(): Promise<void> {
      loading.value = true;
      errorMessage.value = null;
      try {
        tokens.value = await client.listDeckTokens(props.pollId);
      } catch (ex) {
        errorMessage.value = messageFor(ex);
      } finally {
        loading.value = false;
      }
    }

    async function onRevoke(tokenId: string): Promise<void> {
      if (!window.confirm("Revoke this deck token? Any deck using it will stop activating.")) {
        return;
      }
      revokingId.value = tokenId;
      errorMessage.value = null;
      try {
        await client.revokeDeckToken(props.pollId, tokenId);
        await refresh();
      } catch (ex) {
        errorMessage.value = messageFor(ex);
      } finally {
        revokingId.value = null;
      }
    }

    onMounted(() => {
      void refresh();
    });

    return {
      tokens,
      loading,
      revokingId,
      errorMessage,
      onRevoke,
      formatRelative,
      maskedToken
    };
  }
});

function maskedToken(t: { id: string }): string {
  const tail = t.id.slice(-4);
  return `tk_••••••${tail}`;
}

// Inline relative-time helper — will be replaced by the shared @slidev-polls/shared/ui export
// once Task 13 ships. Spec: same day → "today HH:MM", previous day → "yesterday HH:MM",
// same calendar year → "MMM D", earlier years → "YYYY".
function formatRelative(iso?: string | null): string {
  if (!iso) return "never";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const now = new Date();
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const sameDay =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate();
  if (sameDay) return `today ${hh}:${mm}`;
  const yesterday = new Date(now);
  yesterday.setDate(now.getDate() - 1);
  const isYesterday =
    d.getFullYear() === yesterday.getFullYear() &&
    d.getMonth() === yesterday.getMonth() &&
    d.getDate() === yesterday.getDate();
  if (isYesterday) return `yesterday ${hh}:${mm}`;
  if (d.getFullYear() === now.getFullYear()) {
    const months = [
      "Jan",
      "Feb",
      "Mar",
      "Apr",
      "May",
      "Jun",
      "Jul",
      "Aug",
      "Sep",
      "Oct",
      "Nov",
      "Dec"
    ];
    return `${months[d.getMonth()]} ${d.getDate()}`;
  }
  return String(d.getFullYear());
}

function messageFor(ex: unknown): string {
  if (ex instanceof AdminApiError) {
    switch (ex.code) {
      case "AUTH_REQUIRED":
        return "Your session expired — sign back in to manage deck tokens.";
      case "FORBIDDEN":
        return "This poll belongs to a different presenter.";
      case "NOT_FOUND":
        return "This poll or deck token no longer exists.";
      default:
        return ex.problem?.message ?? ex.message;
    }
  }
  return "Network error — couldn't reach the server.";
}
</script>

<style scoped>
.dt__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 18px;
  gap: 16px;
}
.dt__title {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
  margin: 0;
}
.dt__sub {
  font-size: 12px;
  color: var(--sp-fg-subtle);
  margin: 4px 0 0;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.dt__back {
  color: var(--sp-accent, #0b63c9);
  text-decoration: none;
  font-size: 12px;
}
.dt__back:hover {
  text-decoration: underline;
}
.dt__error {
  background: var(--sp-danger-bg);
  color: var(--sp-danger-fg);
  border: 1px solid var(--sp-danger);
  border-radius: var(--sp-radius-sm);
  padding: 10px;
  font-size: 13px;
  margin-bottom: 14px;
}
.dt__table {
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-lg);
  overflow: hidden;
}
.dt__row {
  display: grid;
  grid-template-columns: 1fr 180px 110px 130px 90px;
  padding: 12px 14px;
  font-size: 13px;
  align-items: center;
  border-bottom: 1px solid var(--sp-bg-subtle);
}
.dt__row--head {
  background: var(--sp-bg-muted);
  border-bottom: 1px solid var(--sp-border);
  font-size: 11px;
  color: var(--sp-fg-subtle);
  text-transform: uppercase;
  letter-spacing: 0.06em;
  font-weight: 500;
  padding: 10px 14px;
}
.dt__row:last-child {
  border-bottom: 0;
}
.dt__code {
  font-family: var(--sp-font-mono, ui-monospace, monospace);
  font-size: 12px;
  color: var(--sp-fg-muted);
  background: var(--sp-bg-muted);
  padding: 2px 8px;
  border-radius: var(--sp-radius-sm);
}
.dt__when {
  color: var(--sp-fg-subtle);
  font-size: 12px;
}
.dt__status--live {
  color: var(--sp-success-fg, #1b7a1b);
  font-size: 12px;
}
.dt__status--revoked {
  color: var(--sp-danger-fg, #8b0000);
  font-size: 12px;
}
.dt__empty {
  padding: 32px;
  text-align: center;
  color: var(--sp-fg-subtle);
  font-size: 13px;
}
</style>
