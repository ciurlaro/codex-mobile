# Third-party notices

Codex Mobile packages Codex App Server `0.145.0` (Apache-2.0), Kotlin and
kotlinx libraries (Apache-2.0), AndroidX (Apache-2.0), and their locked runtime
dependencies. Exact versions and hashes are recorded in `docs/sbom.cdx.json`.

Chat math is rendered locally by RaTeX `0.1.13` and its bundled KaTeX fonts
(MIT). No WebView or remote math service is used.

The base APK also bundles the Documents and Telegram providers from the pinned
`ciurlaro/codex-mobile-plugins` revision. Documents includes PdfiumAndroid
(Apache-2.0), PDFium and its packaged BSD-style notices, plus bundled Google ML
Kit text recognition under the ML Kit Terms of Service. Telegram includes TDLib
1.8.66 (Boost Software License 1.0) and statically linked OpenSSL 3.5.7
(Apache-2.0). Their exact locked Java dependencies are recorded in the SBOM.
