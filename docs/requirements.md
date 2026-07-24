# Requirements

## Product outcomes

| ID | Outcome |
|---|---|
| R1 | Package, authenticate, converse, stream, cancel, and restart Codex App Server `0.144.6`. |
| R2 | Let the user grant all-files access and select the absolute `cwd` sent with every turn. |
| R3 | Preserve ordinary shell work through App Server. |
| R4 | Add public GitHub Codex marketplace sources through App Server and render a cache-first catalog during one bounded refresh. |
| R5 | Install standard plugins from any App Server marketplace, but accept Android provider splits only from the canonical `ciurlaro/codex-mobile-plugins` Git origin. |
| R6 | Keep App Server plugin configuration as the sole enablement truth; disabling retains provider code and state, while uninstall removes them. |
| R7 | Preserve existing conversations and notify App Server when provider availability changes. |
| R8 | Accept only closed typed provider schemas, never commands, argv, arbitrary property maps, or executable adapters. |
| R9 | Preserve workspace containment, approvals, cancellation, deadlines, mutation journaling, exact replay, and indeterminate outcomes around every provider call. |
| R10 | Keep App Server as the only standalone child process launched by the Android host. |
| R11 | Preserve official plugins, ChatGPT authentication, foreground lifecycle, Markdown, model selection, and conversations. |
| R12 | Keep user-supplied provider secrets out of add-on artifacts and scope encrypted runtime values independently to each plugin. |

## Constraints

- Provider package URLs use the canonical repository's HTTPS GitHub releases, bounded redirects/downloads, and pinned SHA-256 values.
- Android validates provider package name, version code, and signing certificate through an inherited package session.
- Provider presence, plugin enablement, and provider-specific health remain separate states.
- New chats advertise only enabled installed provider tools and skills; every invocation rechecks current authority and fails closed.
- Split installation and removal may restart the app and complete only after post-restart verification.
- Base updates include exact-version replacements for installed splits in the same Android transaction or follow completed provider removal.
- The base project adds no multiplatform target or speculative provider framework.
