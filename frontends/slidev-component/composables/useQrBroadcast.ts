import { ref, type Ref } from "vue";

/**
 * Cross-window sync for the poll QR overlay.
 *
 * During a talk the presenter window (`/presenter`) and the audience play-mode
 * window are two windows of the same browser on one machine. Slidev only syncs
 * navigation (page/clicks) and drawings across them — there is no public API to
 * register a custom server-synced channel — so the QR toggle, which is not a
 * slide click, cannot ride Slidev's own sync. {@link BroadcastChannel} reaches
 * every same-origin window in the browser, which is exactly the primitive
 * Slidev's state sync falls back to when no dev server is present
 * (`@slidev/client` `state/syncState.ts`). We open our own channel so toggling
 * the QR in the presenter window opens it on the audience screen too.
 *
 * The channel is shared; {@code key} scopes messages so a deck with several
 * polls (and Slidev keeping every slide mounted) doesn't cross-trigger.
 * {@link BroadcastChannel} never echoes a message back to the sender, so the
 * toggling window updates its own {@code open} directly in {@link set}.
 */
export interface QrBroadcast {
  /** Reactive overlay visibility, kept in sync across windows. */
  open: Ref<boolean>;
  /** Set visibility locally and broadcast it to the other windows. */
  set: (value: boolean) => void;
  /** Tear down the channel — call from the host component's unmount hook. */
  stop: () => void;
}

const CHANNEL_NAME = "slidev-polls:qr";

export function useQrBroadcast(key: string): QrBroadcast {
  const open = ref(false);
  let channel: BroadcastChannel | null = null;

  if (typeof BroadcastChannel !== "undefined") {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = (e: MessageEvent) => {
      const data = e.data as { key?: string; open?: boolean } | null;
      if (data && data.key === key) {
        open.value = Boolean(data.open);
      }
    };
  }

  function set(value: boolean): void {
    open.value = value;
    channel?.postMessage({ key, open: value });
  }

  function stop(): void {
    if (channel) {
      channel.onmessage = null;
      channel.close();
      channel = null;
    }
  }

  return { open, set, stop };
}
