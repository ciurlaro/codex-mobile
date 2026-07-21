# Step 02 — Read-only Android authority

**Status:** Superseded historical experiment. The current architecture uses all-files access and Codex's shell.

## Question

Can Codex list and read only documents inside a user-selected SAF tree, with Android validating every request?

## Scope

Add the smallest read-only tool surface: select/revoke one tree, list entries, and read one bounded document. Choose dynamic tools or MCP from measured integration evidence in this step.

## Exit gate

- Every read is bound to a current persisted grant and opaque `ResourceScopeId`.
- Unknown tools, malformed arguments, paths, and out-of-scope identifiers are denied.
- Provider and permission failures become truthful `ToolResult` values.
- No mutating tool is registered or reachable.

## Test matrix

| ID | Case | Evidence |
|---|---|---|
| S02-GRANT-01 | User selects a valid tree and the grant survives relaunch | Device |
| S02-GRANT-02 | Picker cancellation creates no scope | Device |
| S02-GRANT-03 | Revoked, missing, read-only, and non-persistable grants are handled | Device + Fault |
| S02-GRANT-04 | A scope ID cannot be guessed, substituted, or used across app data resets | Unit + Device |
| S02-GRANT-05 | Grant removal immediately blocks later calls | Device |
| S02-LIST-01 | Empty, single-entry, nested, and large directories list deterministically | Device |
| S02-LIST-02 | Unicode, RTL, emoji, dots, slashes-in-display-name, and long names render safely | Device |
| S02-LIST-03 | Files and directories expose only required metadata | Inspection |
| S02-LIST-04 | Provider returns null, duplicate, changing, or inaccessible rows | Fault |
| S02-READ-01 | Empty, text, Unicode, and binary documents return declared results | Device |
| S02-READ-02 | Size and output limits reject oversized content before unbounded allocation | Unit + Device |
| S02-READ-03 | Document deletion or replacement between resolve and open is truthful | Fault |
| S02-READ-04 | Short reads, provider exceptions, and stream failure close resources | Fault |
| S02-SCOPE-01 | Sibling tree, parent tree, foreign URI, raw path, and traversal-like input are denied | Unit + Device |
| S02-SCOPE-02 | Redirected or provider-returned identifiers are revalidated in the original tree | Fault |
| S02-TOOL-01 | Unknown name, missing/extra/wrong-type arguments, and invalid JSON are denied | Unit |
| S02-TOOL-02 | Duplicate call ID is correlated but never treated as proof of prior execution | Unit |
| S02-TOOL-03 | Read calls need no mutation approval and cannot invoke mutation code | Unit + Inspection |
| S02-TOOL-04 | Android's observed bytes/error, not Codex text, determine `ToolResult` | Device |
| S02-PROV-01 | Local, downloads, removable, and cloud-backed providers are sampled where available | Device |
| S02-LIFE-01 | Rotation, background/foreground, and app-server restart retain or reject scope consistently | Device |
| S02-SEC-01 | URIs and document contents are absent from default logs and analytics | Inspection |

## Required decision

Record dynamic tools versus MCP using only implementation size, protocol fit, failure behavior, and testability observed here.

## Result record

- **Tool bridge:** Pinned Codex 0.144.6 dynamic tools register `list_documents` and `read_document` on the existing app-server session. Scripted JSON-RPC tests cover malformed requests and duplicate call-ID correlation; a physical-device run exercised both tools through the bundled app-server and returned only Android-observed results.
- **Authority:** One read-only `ACTION_OPEN_DOCUMENT_TREE` grant maps to a random `ResourceScopeId`. Signed opaque document IDs are bound to the current scope secret, rewalked through provider children, and revalidated against the selected tree before every list or read.
- **Bounds and failures:** Lists stop at 2,048 entries or 512 KiB of serialized entry metadata. Reads accept bounded UTF-8 text only and stop at 64 KiB. Revocation, malformed input, redirects, cycles, stale metadata, binary/invalid text, provider exceptions, short reads, and stream errors return sanitized rejected/failed results.
- **Compatibility:** The cross-Binder provider suite runs on API 26, a physical API 36 device, and API 37. API 26 evidence required the URI form of `ContentResolver.call` and `DataInputStream.readFully`; both newer-overload defects were found and corrected by the minimum-API run.
- **Lifecycle/privacy:** The persisted grant survived force-stop, Activity recreation, foreground/background transitions, and app-server shutdown. URI/content markers were absent from app and provider log checks; the UI exposes only a boolean read-access state.
- **Provider samples:** Isolated local-storage, Downloads, and Google Drive scopes passed real-picker bounded-metadata tests. The Drive sample used a nested folder, discarded names and contents, and was revoked afterward; no cloud mutation occurred. No removable-storage root was present on the test targets.
- **Dynamic tools versus MCP:** Dynamic tools won on measured size and fit: they add registration plus one existing JSON-RPC request/result path, preserve app-server request correlation, clear pending work on process failure, and are testable with both the scripted process seam and bundled runtime. MCP would add configuration, transport lifecycle, and another failure boundary for two app-local tools.
