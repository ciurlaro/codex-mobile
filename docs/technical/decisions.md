# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Keep every module under root-level `modules` | Module roots must be obvious and structurally enforceable | Never |
| Consume fixed `codex-agent` Maven artifacts | The reusable client and Android runtime have their own release lifecycle and must not rebuild with the app | A published API migration requires a new immutable version |
| Keep shared production physically `commonMain`-only | Android implementations in the KMP module would blur ownership and allow accidental coupling | A genuinely portable API requires target implementation |
| Keep runtime mechanisms in `codex-agent-runtime-android` | Process, stream, socket, certificate, SQLite, and binary bootstrap behavior is reusable Android infrastructure | A future platform adds its own runtime artifact |
| Use AndroidX DataStore KMP in common | Preference keys, defaults, serialization, and corruption handling are application semantics | A different shared persistence need is proven |
| Use App Server's official plugin lifecycle only | One supported lifecycle avoids provider/companion coupling and duplicated authority | The pinned protocol changes |
| Track generated protocol declarations in `codex-agent-client` | Protocol provenance and drift verification belong with the published client | Upstream stops defining them |
| Centralize build output under `build/modules` | Clean builds must not depend on source-tree or legacy-module residue | Never |
| Release through protected CI | Production signing material stays isolated from pull-request builds | A stronger external signer is adopted |
