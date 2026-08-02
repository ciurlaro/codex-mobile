# Requirements

## Product outcomes

| ID | Outcome |
|---|---|
| R1 | Package, verify, start, stop, and restart Codex App Server `0.145.0` locally on Android. |
| R2 | Authenticate with ChatGPT and preserve conversation, streaming, cancellation, and history behavior. |
| R3 | Let the user grant storage access and choose the absolute workspace sent as each turn's starting `cwd`. |
| R4 | Preserve ordinary shell work through App Server. |
| R5 | Support official App Server plugins, skills, connectors, install/uninstall, enablement, and MCP authentication. |
| R6 | Preserve Plan mode, structured plan progress, approvals, elicitation, and trusted hooks. |
| R7 | Render selectable Markdown, task lists, code, inline/display math, and complete final answers. |
| R8 | Consume portable runtime and agent behavior from fixed `codex-agent-client` artifacts; keep portable product behavior in `commonMain`. |
| R9 | Consume Android process/runtime mechanisms from `codex-agent-runtime-android`; keep host code limited to product-specific Android mechanisms. |
| R10 | Keep App Server as the only standalone child process and provide no remote-runtime fallback. |
| R11 | Build and release this repository without an adjacent `codex-agent` checkout or agent source build. |

## Constraints

- Only Android is enabled initially.
- Every future supported platform must launch App Server locally.
- Runtime archive and executable checksums are mandatory.
- Protocol framing is strict, bounded, and UTF-8-validating.
- The proxy binds loopback, authenticates, and rejects non-public addresses.
- No provider API, custom extension host, or downloaded executable extension
  is part of the product.
- No production Kotlin source may exist outside the two agreed production
  module source roots.
