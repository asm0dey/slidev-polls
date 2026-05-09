<template>
  <section class="dt" data-testid="deck-tokens-page">
    <header class="dt__head">
      <div>
        <h1 class="dt__title">Deck tokens</h1>
        <p class="dt__sub">
          Tokens authenticate your deck to push poll state. Treat like passwords.
          <router-link :to="{ name: 'poll-edit', params: { pollId } }" class="dt__back">
            ← back to poll
          </router-link>
        </p>
      </div>
      <Button @click="onOpenMintForm" data-testid="deck-token-create">+ New token</Button>
    </header>

    <form class="dt__mint-form" @submit.prevent="onMint" data-testid="mint-form">
      <label class="dt__mint-label">
        Label (optional)
        <input
          v-model="newLabel"
          type="text"
          placeholder="e.g. laptop, conference-wifi"
          class="dt__mint-input"
          data-testid="mint-label"
        />
      </label>
      <button type="submit" :disabled="minting" class="dt__mint-submit" data-testid="mint-submit">
        {{ minting ? "Minting…" : "Mint token" }}
      </button>
    </form>

    <div v-if="justMinted" class="dt__minted" data-testid="minted-banner">
      <p>New token (copy now — it will not be shown again):</p>
      <pre class="dt__code dt__code--big" data-testid="minted-plaintext">{{
        justMinted.plaintext
      }}</pre>
      <button
        type="button"
        class="dt__minted-copy"
        @click="copyPlaintext"
        data-testid="minted-copy"
      >
        {{ copied ? "Copied" : "Copy" }}
      </button>
      <button type="button" class="dt__minted-dismiss" @click="justMinted = null">Dismiss</button>
    </div>

    <p v-if="errorMessage" class="dt__error" role="alert" data-testid="deck-tokens-error">
      {{ errorMessage }}
    </p>

    <div v-if="loading" data-testid="loading">Loading tokens…</div>
    <template v-else>
      <p v-if="tokens.length === 0" class="dt__empty" data-testid="empty">
        No deck tokens yet — mint one above to use this poll inside a Slidev deck.
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
              Revoked {{ formatDate(token.revokedAt) }}
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
            >{{ revokingId === token.id ? "Revoking…" : "×" }}</Button
          >
        </div>
      </div>
    </template>
  </section>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted, type PropType } from "vue";
import type { DeckToken, DeckTokenMinted } from "@slidev-polls/shared";
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
    const newLabel = ref("");
    const minting = ref(false);
    const revokingId = ref<string | null>(null);
    const justMinted = ref<DeckTokenMinted | null>(null);
    const copied = ref(false);
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

    function onOpenMintForm(): void {
      // Scroll/focus the mint form — the form is always visible
      const el = document.querySelector("[data-testid='mint-label']") as HTMLElement | null;
      el?.focus();
    }

    async function onMint(): Promise<void> {
      minting.value = true;
      errorMessage.value = null;
      copied.value = false;
      try {
        const minted = await client.mintDeckToken(props.pollId, {
          label: newLabel.value.trim() || undefined
        });
        justMinted.value = minted;
        newLabel.value = "";
        await refresh();
      } catch (ex) {
        errorMessage.value = messageFor(ex);
      } finally {
        minting.value = false;
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

    async function copyPlaintext(): Promise<void> {
      if (!justMinted.value) return;
      try {
        await navigator.clipboard.writeText(justMinted.value.plaintext);
        copied.value = true;
      } catch {
        // clipboard unavailable — plaintext stays visible for manual copy
      }
    }

    onMounted(() => {
      void refresh();
    });

    return {
      tokens,
      loading,
      newLabel,
      minting,
      revokingId,
      justMinted,
      copied,
      errorMessage,
      onOpenMintForm,
      onMint,
      onRevoke,
      copyPlaintext,
      formatDate,
      formatRelative,
      maskedToken
    };
  }
});

function maskedToken(t: { plaintext?: string | null; id: string }): string {
  if (t.plaintext) return t.plaintext;
  const tail = t.id.slice(-4);
  return `tk_••••••${tail}`;
}

function formatRelative(iso?: string | null): string {
  if (!iso) return "never";
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 60_000) return "just now";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} hr ago`;
  if (diff < 30 * 86_400_000) return `${Math.floor(diff / 86_400_000)} d ago`;
  return new Date(iso).toLocaleDateString();
}

function formatDate(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
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
.dt__mint-form {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  margin-bottom: 18px;
  padding: 12px 14px;
  background: var(--sp-bg-muted);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-lg);
}
.dt__mint-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--sp-fg-subtle);
  flex: 1;
}
.dt__mint-input {
  padding: 6px 10px;
  font-size: 13px;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-sm);
  background: var(--sp-bg);
  color: var(--sp-fg);
  min-width: 200px;
}
.dt__mint-input:focus {
  outline: none;
  border-color: var(--sp-accent, #0b63c9);
}
.dt__mint-submit {
  padding: 6px 14px;
  font-size: 13px;
  background: var(--sp-accent, #0b63c9);
  color: #fff;
  border: none;
  border-radius: var(--sp-radius-sm);
  cursor: pointer;
  white-space: nowrap;
}
.dt__mint-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.dt__table {
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius-lg);
  overflow: hidden;
}
.dt__row {
  display: grid;
  grid-template-columns: 1fr 180px 110px 130px 40px;
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
.dt__code--big {
  font-size: 13px;
  padding: 6px 10px;
  display: block;
  margin: 8px 0;
  word-break: break-all;
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
.dt__minted {
  margin-bottom: 18px;
  padding: 14px;
  background: var(--sp-success-bg);
  border: 1px solid var(--sp-success);
  border-radius: var(--sp-radius-lg);
  font-size: 13px;
  color: var(--sp-success-fg);
}
.dt__minted-copy,
.dt__minted-dismiss {
  margin-top: 8px;
  margin-right: 6px;
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid currentColor;
  border-radius: var(--sp-radius-sm);
  background: transparent;
  cursor: pointer;
  color: inherit;
}
</style>
