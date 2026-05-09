import { ref, type Ref } from "vue";
import { getConfiguredBackend } from "./configureDeckAuthBackend";

// Reactive auth-state composable for the Slidev addon (feature 002). Owns per-browser
// presenter-credential state, persists it in localStorage, and surfaces the distinct
// FR-014 status messages. See specs/002-presenter-auth-gating/data-model.md §DeckAuthState.

export type DeckAuthStatus = "anonymous" | "signed-in-pending" | "signed-in" | "revoked";

export interface DeckAuthState {
  token: string | null;
  tokenId: string | null;
  pollId: string | null;
  label: string | null;
  verifiedAt: string | null;
}

export interface UseDeckAuthReturn {
  status: Ref<DeckAuthStatus>;
  state: Ref<DeckAuthState>;
  message: Ref<string | null>;
  signIn: (token: string) => Promise<void>;
  signInWithCredentials: (username: string, password: string) => Promise<void>;
  signOut: () => void;
  markRevoked: () => void;
}

const STORAGE_KEY = "slidev-polls:deck-auth";
const MIN_TOKEN = 20;
const MAX_TOKEN = 512;
// FR-014 messages — kept verbatim in the source and mirrored in the deck-auth-signin.feature.
const AUTH_FAILURE_MESSAGE = "credential not recognised";
const TRANSPORT_FAILURE_MESSAGE = "couldn't reach server";

function emptyState(): DeckAuthState {
  return { token: null, tokenId: null, pollId: null, label: null, verifiedAt: null };
}

function readStored(): DeckAuthState | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed.token !== "string" || parsed.token.length === 0) return null;
    return {
      token: parsed.token,
      tokenId: typeof parsed.tokenId === "string" ? parsed.tokenId : null,
      pollId: typeof parsed.pollId === "string" ? parsed.pollId : null,
      label: typeof parsed.label === "string" ? parsed.label : null,
      verifiedAt: typeof parsed.verifiedAt === "string" ? parsed.verifiedAt : null
    };
  } catch {
    return null;
  }
}

function writeStored(s: DeckAuthState): void {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
}

function clearStored(): void {
  if (typeof window === "undefined") return;
  window.localStorage.removeItem(STORAGE_KEY);
}

function isPrintableAscii(s: string): boolean {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c < 0x20 || c > 0x7e) return false;
  }
  return true;
}

// Module-singleton so every consumer reads the same reactive state (research.md D-006).
let singleton: UseDeckAuthReturn | null = null;

export function useDeckAuth(baseUrl: string = ""): UseDeckAuthReturn {
  if (singleton) return singleton;

  const status = ref<DeckAuthStatus>("anonymous");
  const state = ref<DeckAuthState>(emptyState());
  const message = ref<string | null>(null);

  // baseUrl arg is treated as a fallback for backwards compat with existing PollResults
  // invocations. configureDeckAuthBackend() wins when set — this is read dynamically
  // on every call so it can be set after the singleton is constructed.
  function currentBase(): string {
    const cfg = getConfiguredBackend();
    return cfg !== "" ? cfg : baseUrl.replace(/\/$/, "");
  }

  async function verify(token: string): Promise<Response> {
    return fetch(`${currentBase()}/api/deck/auth/me`, {
      method: "GET",
      headers: { "X-Deck-Token": token }
    });
  }

  async function signIn(rawToken: string): Promise<void> {
    const token = (rawToken ?? "").trim();
    if (token.length < MIN_TOKEN || token.length > MAX_TOKEN || !isPrintableAscii(token)) {
      status.value = "anonymous";
      message.value = AUTH_FAILURE_MESSAGE;
      clearStored();
      return;
    }

    status.value = "signed-in-pending";
    message.value = null;
    let res: Response;
    try {
      res = await verify(token);
    } catch {
      status.value = "anonymous";
      message.value = TRANSPORT_FAILURE_MESSAGE;
      clearStored();
      state.value = emptyState();
      return;
    }

    if (res.status === 200) {
      const body = (await res.json()) as {
        tokenId: string;
        pollId: string;
        label: string | null;
      };
      const next: DeckAuthState = {
        token,
        tokenId: body.tokenId,
        pollId: body.pollId,
        label: body.label ?? null,
        verifiedAt: new Date().toISOString()
      };
      state.value = next;
      writeStored(next);
      status.value = "signed-in";
      message.value = null;
      return;
    }

    // 401 and every other non-200 collapse to authentication failure; the auth control
    // distinguishes it from the transport-failure branch above via the message ref.
    status.value = "anonymous";
    message.value = AUTH_FAILURE_MESSAGE;
    state.value = emptyState();
    clearStored();
  }

  async function signInWithCredentials(rawUsername: string, rawPassword: string): Promise<void> {
    const username = (rawUsername ?? "").trim();
    const password = rawPassword ?? "";
    if (username.length === 0 || password.length === 0) {
      status.value = "anonymous";
      message.value = AUTH_FAILURE_MESSAGE;
      state.value = emptyState();
      clearStored();
      return;
    }

    status.value = "signed-in-pending";
    message.value = null;
    let res: Response;
    try {
      res = await fetch(`${currentBase()}/api/deck/auth/login`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ username, password })
      });
    } catch {
      status.value = "anonymous";
      message.value = TRANSPORT_FAILURE_MESSAGE;
      state.value = emptyState();
      clearStored();
      return;
    }

    if (res.status === 200) {
      const body = (await res.json()) as {
        token: string;
        tokenId: string;
        pollId: string;
        label: string | null;
      };
      const next: DeckAuthState = {
        token: body.token,
        tokenId: body.tokenId,
        pollId: body.pollId,
        label: body.label ?? null,
        verifiedAt: new Date().toISOString()
      };
      state.value = next;
      writeStored(next);
      status.value = "signed-in";
      message.value = null;
      return;
    }

    status.value = "anonymous";
    message.value = AUTH_FAILURE_MESSAGE;
    state.value = emptyState();
    clearStored();
  }

  function signOut(): void {
    // Local-only per research.md D-002 — no backend call.
    status.value = "anonymous";
    state.value = emptyState();
    message.value = null;
    clearStored();
  }

  function markRevoked(): void {
    status.value = "revoked";
    message.value = AUTH_FAILURE_MESSAGE;
    state.value = emptyState();
    clearStored();
  }

  // Reload-time rehydrate: if localStorage holds a token, go signed-in-pending and verify in
  // the background (research.md D-006). Never block the caller.
  const stored = readStored();
  if (stored) {
    state.value = stored;
    status.value = "signed-in-pending";
    message.value = null;
    (async () => {
      try {
        const res = await verify(stored.token!);
        if (res.status === 200) {
          const body = (await res.json()) as {
            tokenId: string;
            pollId: string;
            label: string | null;
          };
          const next: DeckAuthState = {
            token: stored.token,
            tokenId: body.tokenId,
            pollId: body.pollId,
            label: body.label ?? null,
            verifiedAt: new Date().toISOString()
          };
          state.value = next;
          writeStored(next);
          status.value = "signed-in";
        } else {
          markRevoked();
        }
      } catch {
        // Transport error on reload — stay signed-in-pending would violate FR-007 (zero
        // activation while not currently verified), so collapse to anonymous without a message
        // since the presenter did not initiate this call.
        status.value = "anonymous";
        state.value = emptyState();
        clearStored();
      }
    })();
  }

  singleton = {
    status,
    state,
    message,
    signIn,
    signInWithCredentials,
    signOut,
    markRevoked
  };
  return singleton;
}

// Exposed for tests so they can reset the module-level singleton between specs.
export function __resetUseDeckAuthForTests(): void {
  singleton = null;
}
