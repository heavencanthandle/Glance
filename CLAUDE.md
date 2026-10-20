# Glance (一目 hitome)

@AGENTS.md

The parent folder's `../CLAUDE.md` has the cross-app rules and the `/lines` and `/moon`
contracts.

## Build, test, phone

- `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`. Tests: `GlanceOrderTest`,
  `MoonPhaseTest`.
- Phone: release-signed by the key in `signing/` (gitignored; it signs debug builds too).
  Raise `versionCode`, `:app:assembleRelease`, then
  `adb -d install -r app\build\outputs\apk\release\app-release.apk`.
- To see changes you need a screen lock (even Swipe), the accessibility service on, and the phone
  locked. **Preview** in the app shows the panel without locking.

## Conventions

- Fork of wanderwildwood/hitome. Git remote `upstream` is the original. Keep its history
  mergeable: no mass reformatting, and keep fork-only code in its own files where possible
  (`Moon.kt`, `Hotspot.kt`, `Camera.kt`, `Clock.kt`, `Utilities.kt` are fork-only). `Torch.kt` is
  upstream's (power pressed twice); the fork's own lamp was dropped for it on 2026-10-18.
- The package id `com.wanderwildwood.hitome` must not change. Every provider app allowlists it.
- Sources are in `app/src/main/kotlin`. `LockscreenPanel.kt` (drawing and placement) and
  `MainActivity.kt` (settings) are the big ones.
- Copy follows the README's plain, exact voice ("Moon is Almanac's phase, drawn like
  Almanac's"). Match it in `strings.xml`.

## Cross-app

- Reads Almanac (`com.heavencanthandle.almanac.glance`: `/lines`, `/moon`) and Music Box
  (`com.wanderwildwood.jimeikin.glance/lines`), plus wanderwildwood's other apps.
- A new provider app goes in the manifest `<queries>` (package + provider authority) and in
  the source list in `Glance.kt` / `MainActivity.kt`.
- The moon icon is drawn here from Almanac's `/moon` row. Drawing bugs are fixed in `Moon.kt`.
