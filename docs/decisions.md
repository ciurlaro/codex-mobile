# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Start with one `CodexAgentClient` | One feasibility path does not justify four collaborators | Protocol, auth, transport, or tool bridging gains an independent state machine |
| Keep the process seam outside core | Process mechanics are infrastructure, not domain policy | A second runtime implementation needs the same tested contract |
| Android owns operation truth | Codex cannot observe Android permission and provider outcomes authoritatively | Never |
| Use opaque SAF scopes | Paths cannot represent every Android document provider | Never for Android device resources |
| Require resolved approval for mutations | Provider text is untrusted and can be misleading | A narrowly defined operation is proven safe to pre-authorize |
| Implement the mutation journal in Step 04 | Crash consistency should not contaminate mutation feasibility | Step 04 starts |
| Defer foreground service work | It adds lifecycle restrictions without proving the runtime premise | Visible-Activity execution works and background continuation is required |
| Keep KMP disabled | Hypothetical portability is not a current product requirement | iOS work is funded and measured migration cost is known |
| Do not promise exactly-once tools | Process death can obscure whether an external side effect occurred | Never; reconcile instead |
| Keep tests ignored until real | A passing placeholder is false evidence | Remove each ignore only with its implementation |
| Package a pinned app-server as an ARM64 native library | The verified musl executable runs from the APK native-library directory; app-private state, Android CAs, and an allowlisted loopback proxy supply the Android environment it needs | Codex ships an Android-native runtime or networking path |
| Use device-code authentication from another device while execution is Activity-bound | Android blocks this app UID's network while a same-device browser is foregrounded | Step 05 provides foreground execution or Android changes the restriction |
| Reject app-server diagnostic SQLite rows at the private database boundary | The upstream diagnostic layer records prompt and response material and has no supported disable switch | Upstream provides a supported privacy-safe logging mode |
| Bridge Android capabilities with app-server dynamic tools | On pinned Codex 0.144.6, two dynamic tools fit the existing JSON-RPC session: one registration array and one `item/tool/call` request/result path. MCP would add server configuration, transport lifecycle, and another failure boundary without sharing pressure. Duplicate call IDs remain correlation only, and scripted plus live-runtime tests cross the same bridge | The pinned app-server removes the experimental dynamic-tool API or another client must share the Android tools |

## Deliberately unsettled

- Whether coordination belongs in the initial ViewModel or a `SessionController`.
- Room schema/module shape.
- Foreground-service details.
- Additional providers and KMP.

Record these as experiment results, not architecture guesses.
