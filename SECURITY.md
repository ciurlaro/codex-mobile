# Security

Report vulnerabilities through this repository's private GitHub Security Advisory flow. Do not open a public issue for signing-key exposure, authentication bypass, provider-source confusion, secret disclosure, path escape, or mutation replay.

The production Android signing key never belongs in an issue, pull request, repository secret available to CI verification, or local build log. Provider packages are trusted only after canonical Git-origin, hash, package, version, split, ABI, signer, and schema verification.
