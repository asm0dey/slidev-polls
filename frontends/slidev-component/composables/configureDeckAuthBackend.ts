// Module that holds the configured backend URL for deck auth, independent of the
// useDeckAuth singleton. Callers invoke configureDeckAuthBackend() once (e.g. from
// a slide's setup block) so that every auth request goes to the correct origin even
// when the useDeckAuth singleton was constructed before the URL was known.

let configured: string = "";

export function configureDeckAuthBackend(url: string): void {
  configured = (url ?? "").replace(/\/$/, "");
}

export function getConfiguredBackend(): string {
  return configured;
}

export function __resetConfiguredBackendForTests(): void {
  configured = "";
}
