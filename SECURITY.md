# Security

Report vulnerabilities through this repository's private GitHub Security
Advisory flow. Do not open a public issue for signing-key exposure,
authentication bypass, binary-integrity failure, proxy escape, path escape,
protocol framing bypass, or credential disclosure.

The production signing key must not appear in an issue, pull request,
repository-accessible verification secret, or build log. Runtime binaries and
archives are pinned and verified before execution. The Android CONNECT proxy
binds loopback, requires per-runtime credentials, permits TLS port 443 only,
and rejects private, local, reserved, and malformed destinations.
