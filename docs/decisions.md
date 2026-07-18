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

## Deliberately unsettled

- Dynamic tools versus MCP.
- Exact Codex launch, extraction, and update strategy.
- Credential and session persistence behavior.
- Whether coordination belongs in the initial ViewModel or a `SessionController`.
- Room schema/module shape.
- Foreground-service details.
- Additional providers and KMP.

Record these as experiment results, not architecture guesses.
