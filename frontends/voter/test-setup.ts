// jsdom 26 ships a `window.localStorage` that is a plain object without the Storage method
// bindings (setItem/getItem/removeItem/key/length/clear). Swap in a Map-backed polyfill so
// vitest suites that exercise localStorage-dependent code work regardless of the jsdom build.
//
// Picked up via vitest.config.ts `test.setupFiles`. Centralising here keeps individual tests
// from each having to repeat the polyfill.

class InMemoryStorage implements Storage {
  private store = new Map<string, string>();

  get length(): number {
    return this.store.size;
  }

  clear(): void {
    this.store.clear();
  }

  getItem(key: string): string | null {
    return this.store.has(key) ? (this.store.get(key) as string) : null;
  }

  key(index: number): string | null {
    const keys = Array.from(this.store.keys());
    return keys[index] ?? null;
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }

  setItem(key: string, value: string): void {
    this.store.set(key, String(value));
  }
}

Object.defineProperty(window, "localStorage", {
  value: new InMemoryStorage(),
  configurable: true,
  writable: true
});
