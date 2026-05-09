# @slidev-polls/shared

Shared TypeScript types, REST/SSE clients, and Vue UI primitives consumed by
the [slidev-polls](https://github.com/asm0dey/slidev-polls) suite (Slidev
addon, voter SPA, backoffice SPA).

Ships untranspiled `.ts` and `.vue` source. Intended for projects that build
with Vite or another bundler that understands TypeScript and Vue SFCs.

## Install

```bash
npm install @slidev-polls/shared
# or
bun add @slidev-polls/shared
```

`vue@^3.5` is a peer dependency.

## Exports

- `@slidev-polls/shared` — types, `apiClient`, `sseClient`
- `@slidev-polls/shared/ui` — Vue UI components (`ResultsPanel`,
  `AllowedOriginsField`, theme helpers)
- `@slidev-polls/shared/tokens.css` — CSS design tokens

## License

GPL-3.0-or-later. See [LICENSE](./LICENSE).
