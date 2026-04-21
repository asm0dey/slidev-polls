import type { Ref } from "vue";

// Type surface only: runtime body lands in T022. Tests (T011–T015) import the symbols
// here; the runtime must throw so the red-phase assertions actually fail.

export type DeckAuthStatus =
  | "anonymous"
  | "signed-in-pending"
  | "signed-in"
  | "revoked";

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
  signOut: () => void;
  markRevoked: () => void;
}

export function useDeckAuth(_baseUrl?: string): UseDeckAuthReturn {
  throw new Error("not-yet-implemented");
}
