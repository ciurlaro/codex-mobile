# Visual baselines

Reviewed, app-owned Codex Mobile PNGs belong here as `<scenario-id>.png` after explicit
human review. The nine checked-in files are synthetic scenarios; none contains private
reference content or account data.

`./gradlew visualCapture` writes persistent review candidates to
`/sdcard/Download/codex-mobile-visual-candidates/`; comparison failures go to the neighboring
`codex-mobile-visual-failures/` directory. It never copies or accepts candidates into this
baseline directory. Private reference screenshots and system keyboard pixels must never be
used as baselines.

The canonical device is an English (United States) API 37 emulator at 1080×2400, 420 dpi,
font scale 1.0, with window, transition, and animator scales disabled. PixelCopy captures
only the app window, so System UI is excluded. `./gradlew visualCheck` verifies the device
configuration before comparing, and `./gradlew visualCapture` never auto-accepts changes.
