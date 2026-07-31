# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Keep every module under root-level `modules` | Module roots must be obvious and structurally enforceable | Never |
| Use one common production module and one Android production module | This is the smallest ownership graph that keeps portable behavior common and mechanisms platform-specific | A second supported platform proves a new boundary |
| Keep shared production physically `commonMain`-only | Android implementations in the KMP module would blur ownership and allow accidental coupling | A genuinely portable API requires target implementation |
| Use Java `ProcessBuilder` and streams | They are production JDK/Android APIs and avoid a snapshot process dependency | A stable portable process API demonstrably replaces less code |
| Keep socket mechanism in Android and policy in common | Java sockets are stable on Android; authorization and address rules are portable | Another platform supplies its own local runtime |
| Use AndroidX DataStore KMP in common | Preference keys, defaults, serialization, and corruption handling are application semantics | A different shared persistence need is proven |
| Use App Server's official plugin lifecycle only | One supported lifecycle avoids provider/companion coupling and duplicated authority | The pinned protocol changes |
| Track generated protocol declarations | Public generated wire declarations must remain reviewable and deterministic | Upstream stops defining them |
| Centralize build output under `build/modules` | Clean builds must not depend on source-tree or legacy-module residue | Never |
| Release through protected CI | Production signing material stays isolated from pull-request builds | A stronger external signer is adopted |
