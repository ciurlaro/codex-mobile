# Contributing

Open an issue or pull request against the default branch. Keep changes focused, preserve Codex App Server `0.144.6`, and run the repository verification commands when practical; GitHub Actions is the authoritative full build.

Ordinary Codex plugins remain external. Android provider implementations are reviewed in `ciurlaro/codex-mobile-plugins`; this repository owns the generic host contract, provenance policy, lifecycle, authority checks, and thin base-app feature wrappers.

Contributions are licensed under `GPL-3.0-or-later`. Changes that touch ML Kit linkage must preserve or deliberately replace the narrow additional permission in `LICENSES/MLKIT-EXCEPTION.txt`.
