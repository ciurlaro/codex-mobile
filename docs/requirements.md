# Requirements

## Product outcomes

| ID | Outcome |
|---|---|
| R1 | Package, authenticate, converse, stream, cancel, and restart Codex App Server `0.145.0`. |
| R2 | Let the user grant all-files access and select the absolute `cwd` sent with every turn. |
| R3 | Preserve ordinary shell work through App Server. |
| R4 | Materialize public GitHub Codex marketplaces as bounded, atomically refreshed local snapshots, register them through App Server, and render a cache-first catalog during one bounded refresh. |
| R5 | Install standard plugins from any registered marketplace, but activate Android providers only when canonical marketplace metadata matches code bundled from the pinned `ciurlaro/codex-mobile-plugins` revision. |
| R6 | Keep App Server plugin configuration as the sole enablement truth; disabling retains provider state, while uninstall removes activation, secrets, and owned data after cleanup. |
| R7 | Preserve existing conversations and notify App Server when provider availability changes. |
| R8 | Accept only closed typed provider schemas, never commands, argv, arbitrary property maps, or executable adapters. |
| R9 | Preserve workspace containment, approvals, cancellation, deadlines, mutation journaling, exact replay, and indeterminate outcomes around every provider call. |
| R10 | Keep App Server as the only standalone child process launched by the Android host. |
| R11 | Preserve official plugins, ChatGPT authentication, foreground lifecycle, Markdown, model selection, and conversations. |
| R12 | Keep user-supplied provider secrets out of add-on artifacts and scope encrypted runtime values independently to each plugin. |
| R13 | Expose Codex Plan mode, structured plan progress, and trusted workspace hooks through the native App Server protocol. |
| R14 | Render native inline and display math, keep chat text selectable, and let users copy a complete final answer. |

## Constraints

- Native provider code is never downloaded at runtime; unknown providers require a newer host APK.
- Bundled provider IDs, API/host ranges, versions, schemas, entry points, settings, and MCP names match canonical bounded metadata before activation.
- Provider presence, plugin enablement, and provider-specific health remain separate states.
- New chats advertise only enabled installed provider tools and skills; every invocation rechecks current authority and fails closed.
- Provider installation and removal keep the app process alive; installations become visible to tools in the next new chat.
- A full monolithic base update removes legacy provider splits and migrates their lifecycle records without clearing app data.
- The base project adds no multiplatform target or speculative provider framework.
