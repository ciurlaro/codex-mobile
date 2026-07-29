# Decisions

| Decision | Why | Revisit when |
|---|---|---|
| Consolidate the agent and local host as `codex-agent-runtime` KMP | One common owner keeps protocol, process, proxy, persistence, and lifecycle behavior consistent across platforms | A proven platform requires a separately released subset |
| Pin App Server `0.145.0` and use dynamic tools | It preserves the Codex harness while typed providers enforce local authority | The pinned protocol gains a better native extension API |
| Use the standard Codex Git marketplace | One catalog, lifecycle, skill UX, and enablement truth work across hosts | App Server replaces this surface |
| Restrict Android code to the canonical provider Git origin | Ordinary Codex plugins stay portable while executable same-UID add-ons remain owner-reviewed and officially signed | A stronger platform-native trust mechanism replaces repository provenance |
| Bundle reviewed Android providers from a pinned source revision | OEMs may kill the host for split updates despite `setDontKillApp`; activation must not update the running package | Android guarantees restart-free first-party feature delivery |
| Persist only provider activation lifecycle | Interrupted App Server installation/removal must recover without becoming enablement state | App Server makes provider activation transactional |
| Keep all runtime production code in `commonMain` | Each supported platform must launch the same local App Server with equivalent security and lifecycle behavior | Never; add only complete platform bootstraps |
| Use direct in-process Kotlin entry points | They need no transport, provider process, Binder API, or generic backend framework | Isolation is required by measured security evidence |
| Show cached catalog data during one bounded refresh | `plugin/list` returns one response; cache-first UI improves latency without fake streaming or retry storms | App Server provides an incremental catalog protocol |
| Prohibit provider helper processes | A single typed authority path keeps execution, retry, environment, and packaging auditable | Never; standalone work belongs to App Server |
| Use one global provider-tool mutex | It closes disablement and pre-dispatch races with the smallest policy | Measured contention requires narrower locks |
| Journal provider mutations only | Provider side effects need exact replay and no-resubmit recovery; shell work already has App Server semantics | App Server supplies equivalent recovery |
| Encrypt user-supplied secrets per plugin with Android Keystore | Add-ons install independently, disablement retains configuration, and confirmed uninstall removes only that provider's namespace | Providers require isolation across independently signed Android UIDs |
| Release through protected GitHub CI | Expensive builds run remotely and production signing secrets never enter pull-request jobs or local build processes | An external signer provides a smaller or stronger trust boundary |
| Require one-use user approval for Auto-review typed mutations | App Server `0.145.0` dynamic tools have no equivalent Guardian bridge | The pinned protocol exposes equivalent authority |
| Use Android package/data facilities as authority | Android owns monolithic updates, signing, and full app-data erasure; the host owns bundled-provider activation | Platform contracts change |
