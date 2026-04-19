<template>
  <section class="deck-tokens-page">
    <header class="header">
      <router-link :to="{ name: 'poll-edit', params: { pollId } }" class="back-link">
        ← back to poll
      </router-link>
      <h1>Deck tokens</h1>
      <p class="help">
        A deck token lets a Slidev slide auto-activate its question when you navigate to it.
        Mint a token, copy the plaintext, and paste it into the <code>deckToken</code> prop of
        <code>&lt;PollResults /&gt;</code>. The plaintext is shown once — we only persist its hash.
      </p>
    </header>

    <form class="mint-form" @submit.prevent="onMint" data-testid="mint-form">
      <label>
        Label (optional)
        <input
          v-model="newLabel"
          type="text"
          placeholder="e.g. laptop, conference-wifi"
          data-testid="mint-label"
        />
      </label>
      <button type="submit" :disabled="minting" data-testid="mint-submit">
        {{ minting ? "Minting…" : "Mint token" }}
      </button>
    </form>

    <div v-if="justMinted" class="minted-banner" data-testid="minted-banner">
      <strong>Copy this plaintext — you won't see it again:</strong>
      <pre class="plaintext" data-testid="minted-plaintext">{{ justMinted.plaintext }}</pre>
      <button type="button" @click="copyPlaintext" data-testid="minted-copy">
        {{ copied ? "Copied" : "Copy" }}
      </button>
      <button type="button" class="secondary" @click="justMinted = null">Dismiss</button>
    </div>

    <p v-if="errorMessage" class="error" data-testid="deck-tokens-error">{{ errorMessage }}</p>

    <section v-if="loading" data-testid="loading">Loading tokens…</section>
    <section v-else>
      <p v-if="tokens.length === 0" data-testid="empty">
        No deck tokens yet — mint one above to use this poll inside a Slidev deck.
      </p>
      <table v-else class="tokens" data-testid="tokens-table">
        <thead>
          <tr>
            <th>Label</th>
            <th>Minted</th>
            <th>Status</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="token in tokens" :key="token.id" :data-testid="`row-${token.id}`">
            <td>{{ token.label || "—" }}</td>
            <td>{{ formatDate(token.createdAt) }}</td>
            <td>
              <span v-if="token.revokedAt" class="revoked">
                Revoked {{ formatDate(token.revokedAt) }}
              </span>
              <span v-else class="live">Live</span>
            </td>
            <td>
              <button
                v-if="!token.revokedAt"
                type="button"
                class="danger"
                :disabled="revokingId === token.id"
                :data-testid="`revoke-${token.id}`"
                @click="onRevoke(token.id)"
              >
                {{ revokingId === token.id ? "Revoking…" : "Revoke" }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </section>
</template>

<script lang="ts">
import { defineComponent, ref, onMounted, type PropType } from "vue";
import type { DeckToken, DeckTokenMinted } from "@polls/shared";
import { AdminApiClient, AdminApiError, defaultAdminClient } from "../lib/admin-api";

export default defineComponent({
  name: "DeckTokensPage",
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
        // clipboard unavailable (permissions, insecure context) — the plaintext stays visible in
        // the <pre> block so the presenter can still copy manually.
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
      onMint,
      onRevoke,
      copyPlaintext,
      formatDate
    };
  }
});

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
.deck-tokens-page {
  max-width: 720px;
  margin: 2rem auto;
  font-family: system-ui, sans-serif;
}
.header {
  margin-bottom: 1.5rem;
}
.back-link {
  display: inline-block;
  margin-bottom: 0.5rem;
  color: #0b63c9;
  text-decoration: none;
}
.help {
  color: #555;
  font-size: 0.95rem;
}
.mint-form {
  display: flex;
  gap: 0.5rem;
  align-items: end;
  margin-bottom: 1rem;
}
.mint-form input {
  padding: 0.45rem 0.6rem;
  min-width: 240px;
}
.minted-banner {
  background: #e8f4ff;
  border: 1px solid #b8ddff;
  border-radius: 6px;
  padding: 0.75rem 1rem;
  margin-bottom: 1rem;
}
.plaintext {
  background: #0e1622;
  color: #eaf0f6;
  padding: 0.5rem 0.75rem;
  border-radius: 4px;
  overflow-x: auto;
  font-family: ui-monospace, monospace;
}
.tokens {
  width: 100%;
  border-collapse: collapse;
}
.tokens th,
.tokens td {
  border-bottom: 1px solid #eee;
  padding: 0.5rem 0.25rem;
  text-align: left;
}
.revoked {
  color: #8b0000;
}
.live {
  color: #1b7a1b;
}
.error {
  color: #b10000;
}
button.danger {
  background: #fff2f2;
  border: 1px solid #f4b4b4;
  color: #b10000;
  padding: 0.3rem 0.6rem;
  cursor: pointer;
}
button.secondary {
  margin-left: 0.5rem;
}
</style>
