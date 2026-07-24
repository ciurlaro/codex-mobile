# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Put App Server mechanics behind `CodexRuntime` | Protocol code must not import processes, streams, or Android | A second real runtime appears |
| Pin App Server `0.144.6` and use dynamic tools | It preserves the Codex harness while typed providers enforce local authority | The pinned protocol gains a better native extension API |
| Use the standard Codex Git marketplace | One catalog, lifecycle, skill UX, and enablement truth work across hosts | App Server replaces this surface |
| Restrict Android code to the canonical provider Git origin | Ordinary Codex plugins stay portable while executable same-UID add-ons remain owner-reviewed and officially signed | A stronger platform-native trust mechanism replaces repository provenance |
| Install Android providers as signed feature splits | Android enforces package identity and signer, uninstall removes code, and the base stays lean | Android offers an equally strict first-party add-on mechanism |
| Persist only provider package lifecycle | Package updates must survive process replacement without becoming enablement state | PackageInstaller offers an atomic continuation into App Server installation |
| Keep schemas and semantic DTOs portable | Shared contracts help other targets without converting the host to multiplatform or inventing abstractions | A second host needs additional proven common logic |
| Use direct in-process Kotlin entry points | They need no transport, provider process, Binder API, or generic backend framework | Isolation is required by measured security evidence |
| Show cached catalog data during one bounded refresh | `plugin/list` returns one response; cache-first UI improves latency without fake streaming or retry storms | App Server provides an incremental catalog protocol |
| Prohibit provider helper processes | A single typed authority path keeps execution, retry, environment, and packaging auditable | Never; standalone work belongs to App Server |
| Use one global provider-tool mutex | It closes disablement and pre-dispatch races with the smallest policy | Measured contention requires narrower locks |
| Journal provider mutations only | Provider side effects need exact replay and no-resubmit recovery; shell work already has App Server semantics | App Server supplies equivalent recovery |
| Encrypt user-supplied secrets per plugin with Android Keystore | Add-ons install independently, disablement retains configuration, and confirmed uninstall removes only that provider's namespace | Providers require isolation across independently signed Android UIDs |
| Release through protected GitHub CI | Expensive builds run remotely and production signing secrets never enter pull-request jobs or local build processes | An external signer provides a smaller or stronger trust boundary |
| Keep Auto-review mutations unavailable | App Server `0.144.6` dynamic tools have no equivalent Guardian bridge | The pinned protocol exposes equivalent authority |
| Use Android package/data facilities as authority | Android owns split installation, removal, signing, and full app-data erasure | Platform contracts change |
