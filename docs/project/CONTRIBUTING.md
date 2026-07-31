# Contributing

Open an issue or pull request against the default branch. Keep changes focused,
preserve the pinned Codex App Server protocol and runtime identity, and keep
portable production behavior in
`modules/multiplatform/codex-shared/src/commonMain`.

Android code is reserved for concrete platform mechanisms. Do not add a remote
runtime, provider framework, companion-repository dependency, snapshot process
library, or alternative production source root.

Run the repository structural check and the focused Gradle tasks when
practical; GitHub Actions is the authoritative complete build.

Contributions are licensed under `GPL-3.0-or-later`.
