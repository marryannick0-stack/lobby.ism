---
paths:
  - "server/**"
---

# Server rules (Nakama TypeScript runtime)

- Target is ES5 and Nakama's Goja runtime: no `async`, no ES modules, no Node APIs.
- `InitModule` and every registered RPC must be plain, named, top-level `function` declarations. Nakama reads them by name.
- Every tunable lives in `src/config.ts` (`GameConfig`).
- Validate every client input. Errors are `{ message, code: nkruntime.Codes.X }`, never stack traces.
- PostGIS points are (longitude, latitude). Tests check the parameter order; keep them green.
- Never log a player's position.
- Run `npm test` (compiled module against a fake Nakama) before pushing.
