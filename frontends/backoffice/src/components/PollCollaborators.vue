<script setup lang="ts">
import { onMounted, ref } from "vue";
import type { CollaboratorView } from "@slidev-polls/shared";
import { defaultAdminClient, type AdminApiClient } from "../lib/admin-api";
import { describeError } from "../lib/describe-error";

const props = defineProps<{
  pollId: string;
  apiClient?: Pick<AdminApiClient, "listCollaborators" | "addCollaborator" | "removeCollaborator">;
}>();
const api = props.apiClient ?? defaultAdminClient;

const collaborators = ref<CollaboratorView[]>([]);
const newCollaborator = ref("");
const error = ref<string | null>(null);

async function refresh() {
  try {
    collaborators.value = await api.listCollaborators(props.pollId);
  } catch (e: unknown) {
    error.value = describeError(e, "could not load collaborators");
  }
}

async function add() {
  error.value = null;
  try {
    await api.addCollaborator(props.pollId, newCollaborator.value.trim());
    newCollaborator.value = "";
  } catch (e: unknown) {
    error.value = describeError(e, "could not add collaborator");
    return;
  }
  try {
    await refresh();
  } catch {
    // refresh failure does not override the add-success state
  }
}

async function remove(username: string) {
  error.value = null;
  try {
    await api.removeCollaborator(props.pollId, username);
  } catch (e: unknown) {
    error.value = describeError(e, "could not remove collaborator");
    return;
  }
  try {
    await refresh();
  } catch {
    // refresh failure does not override the remove-success state
  }
}

onMounted(() => {
  void refresh();
});
</script>

<template>
  <section class="pc">
    <h3 class="pc__heading">Collaborators</h3>
    <p class="pc__hint">Removing a collaborator revokes the deck tokens they created.</p>
    <ul class="pc__list">
      <li v-for="c in collaborators" :key="c.username" class="pc__item">
        <span class="pc__username">{{ c.username }}</span>
        <button
          type="button"
          class="pc__remove"
          :aria-label="`Remove ${c.username}`"
          @click="remove(c.username)"
        >
          Remove
        </button>
      </li>
    </ul>
    <form class="pc__form" @submit.prevent="add">
      <label class="pc__input-label" aria-label="New collaborator username">
        <input
          name="newCollaborator"
          v-model="newCollaborator"
          placeholder="username"
          class="pc__input"
          aria-label="New collaborator username"
        />
      </label>
      <button type="submit" class="pc__add" :disabled="!newCollaborator.trim()">Add</button>
    </form>
    <p v-if="error" role="alert" class="pc__error">{{ error }}</p>
  </section>
</template>

<style scoped>
.pc {
  margin-top: 24px;
  border: 1px solid var(--sp-border, #ddd);
  border-radius: var(--sp-radius-lg, 8px);
  padding: 16px;
}

.pc__heading {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  font-weight: 600;
  color: var(--sp-fg);
  margin: 0 0 8px;
}

.pc__hint {
  font-size: 11px;
  color: var(--sp-fg-subtle);
  margin: 0 0 12px;
}

.pc__list {
  list-style: none;
  margin: 0 0 12px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.pc__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  background: var(--sp-bg-muted);
  border-radius: var(--sp-radius-sm, 4px);
  font-size: 13px;
}

.pc__username {
  color: var(--sp-fg);
  font-family: var(--sp-font-mono);
}

.pc__remove {
  font-size: 11px;
  color: var(--sp-danger-fg, #b71c1c);
  background: transparent;
  border: 1px solid var(--sp-danger, #f5c6cb);
  border-radius: var(--sp-radius-sm, 4px);
  padding: 2px 8px;
  cursor: pointer;
  font-family: var(--sp-font-sans);
}

.pc__remove:hover {
  background: var(--sp-danger-bg, #fdecea);
}

.pc__form {
  display: flex;
  gap: 6px;
}

.pc__input-label {
  flex: 1;
  display: contents;
}

.pc__input {
  flex: 1;
  padding: 6px 8px;
  font-family: var(--sp-font-sans);
  font-size: 13px;
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius, 6px);
  background: var(--sp-bg);
  color: var(--sp-fg);
}

.pc__input::placeholder {
  color: var(--sp-fg-faint);
}

.pc__add {
  padding: 6px 14px;
  font-size: 12px;
  font-weight: 500;
  font-family: var(--sp-font-sans);
  border: 1px solid var(--sp-border);
  border-radius: var(--sp-radius, 6px);
  background: var(--sp-bg);
  color: var(--sp-fg);
  cursor: pointer;
}

.pc__add:hover {
  background: var(--sp-bg-muted);
}

.pc__error {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--sp-danger-fg, #b71c1c);
  background: var(--sp-danger-bg, #fdecea);
  border: 1px solid var(--sp-danger, #f5c6cb);
  border-radius: var(--sp-radius-sm, 4px);
  padding: 6px 10px;
}
</style>
