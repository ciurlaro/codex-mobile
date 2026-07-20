# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Start with one `CodexAgentClient` | One feasibility path does not justify four collaborators | Protocol, auth, transport, or tool bridging gains an independent state machine |
| Keep the process seam outside core | Process mechanics are infrastructure, not domain policy | A second runtime implementation needs the same tested contract |
| Android owns operation truth | Codex cannot observe Android permission and provider outcomes authoritatively | Never |
| Use opaque SAF scopes | Paths cannot represent every Android document provider | Never for Android device resources |
| Require exact resolved-plan approval for mutations | Provider text is untrusted, call IDs are only correlation, and resolved state can become stale | A narrowly defined operation is proven safe to pre-authorize |
| Use app-private native SQLite for the mutation journal | One table, single-row compare-and-set transitions, `SQLiteOpenHelper` migration, and platform fault injection satisfy current recovery needs without another dependency | Relationships or query complexity measurably justify Room |
| Use one explicitly user-started `dataSync` foreground service for an active session | Step 01 proved that same-device browser authentication backgrounds the Activity and blocks its network; one service now owns the single client/controller with a private ongoing notification, bounded stop, `START_NOT_STICKY`, and no scheduler or boot receiver | Android provides a narrower supported primitive or the product no longer needs continuation outside a visible Activity |
| Keep KMP disabled | Hypothetical portability is not a current product requirement | iOS work is funded and measured migration cost is known |
| Do not promise exactly-once tools | Process death can obscure whether an external side effect occurred | Never; reconcile instead |
| Keep tests ignored until real | A passing placeholder is false evidence | Remove each ignore only with its implementation |
| Package a pinned app-server as an ARM64 native library | The verified musl executable runs from the APK native-library directory; app-private state, Android CAs, and an allowlisted loopback proxy supply the Android environment it needs | Codex ships an Android-native runtime or networking path |
| Keep device-code authentication inside the user-started foreground session | The service remains the one runtime owner while the same-device browser is foregrounded; no code or credential is delegated to another app component | The provider supplies an Android-native sign-in flow |
| Use Android's native app-data reset for full local erasure | `ActivityManager.clearApplicationUserData()` removes private state, permissions, notifications, and URI grants using the platform's authoritative lifecycle without teaching the app every storage location; provider-owned files remain outside the app sandbox | Android changes the documented clear-data contract |
| Reject app-server diagnostic SQLite rows at the private database boundary | The upstream diagnostic layer records prompt and response material and has no supported disable switch | Upstream provides a supported privacy-safe logging mode |
| Bridge Android capabilities with app-server dynamic tools | On pinned Codex 0.144.6, the three bounded app-local tools fit the existing JSON-RPC session: one registration array and one `item/tool/call` request/result path. MCP would add server configuration, transport lifecycle, and another failure boundary without sharing pressure. Duplicate call IDs remain correlation only, and scripted plus live-runtime tests cross the same bridge | The pinned app-server removes the experimental dynamic-tool API or another client must share the Android tools |
| Omit AGP's encrypted dependency-info APK block | Its randomized ciphertext prevents byte-identical APKs. Strict dependency locks, checksum verification metadata, and the checked-in CycloneDX SBOM provide deterministic dependency evidence instead | Distribution policy requires the Play SDK block and replaces byte-identical APK reproduction |

## Deliberately unsettled

- Additional providers and KMP.

Record these as experiment results, not architecture guesses.
