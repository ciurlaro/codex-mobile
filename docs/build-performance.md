# Build performance

Measurements were recorded on 2026-07-28 on an Apple Silicon MacBook Air running macOS 26.5.2, Java 17, Gradle 9.4.1, Android SDK 37, and NDK 29.0.14206865. The legacy tree is commit `e41fee98f163ee3c9724f5273db3a261bd48e7ee`; both trees use provider revision `a40fefe7c3a60da14e65fef05106a07e1734afdf`.

Native App Server and provider binaries were prepared before local measurements. Their one-time downloads and TDLib/OpenSSL compilation are therefore excluded. The clean comparison disables Gradle's build cache but retains downloaded dependencies and native inputs. The warm result is the second identical configuration-cache build. The incremental result adds a comment to `AppViewModelConstants.kt`, assembles debug, and then restores the file.

| Local scenario | Legacy | Refactored | Change |
|---|---:|---:|---:|
| Clean debug APK (`clean assembleDebug --no-build-cache`) | 45.93 s | 134.33 s | +88.40 s (+192.5%) |
| Warm no-op debug APK (`assembleDebug`) | 4.04 s | 3.02 s | -1.02 s (-25.2%) |
| One-file incremental debug APK | 6.82 s | 5.12 s | -1.70 s (-24.9%) |
| Signed release APK and AAB | 151.03 s | 299.66 s | +148.63 s (+98.4%) |

The clean regression is partly a scope correction: the legacy root `clean` did not clean the App Server client and provider API included builds, while the consolidated runtime is now a root project and is rebuilt. The signed release regression remains an optimization target; no build-scan attribution was assumed. Warm and incremental developer loops both improved.

The refactored complete local verification graph (`test`, common runtime host tests, debug APK, test APK, and lint) took 654.41 s after lock and lint invalidation. The second identical run reused the configuration cache and completed in 3.51 s. A signed release with strict dependency locks took 472.28 s in the earlier cold verification pass; the controlled result above is the comparable warmed build.

After the runtime-output and device-test fixes on 2026-07-29, the post-clean complete 314-task graph took 248 s. The first no-op verification took 6.28 s to store its task-specific configuration cache; the identical rerun reused that cache and took 2.44 s. The final signed APK/AAB build took 745.55 s, and the subsequent no-build-cache reproducibility build took 660.22 s. Its APK was byte-for-byte identical.

## GitHub Actions

The last successful pre-refactor push workflow took 25 minutes 8 seconds end to end. Its verification step took 19 minutes 58 seconds and its signed release/reproducibility step took 3 minutes 49 seconds ([run 30354365274](https://github.com/ciurlaro/codex-mobile/actions/runs/30354365274)).

A post-refactor remote duration cannot exist until these changes are pushed. The updated workflows cancel superseded runs, restore Gradle state, compile the KMP conformance suites explicitly, and reuse the single verified release candidate in the publishing workflow. Add the first successful post-refactor run to this report after publication; local timings are not presented as a substitute for the Linux runner result.
