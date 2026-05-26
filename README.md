# Plymouth Bins

Android app for Plymouth City Council bin collection schedules. Scrapes the council's
AchieveForms-based bin day lookup, surfaces upcoming collections, schedules notifications,
and exposes a home-screen widget.

## Features

- Direct API integration with `plymouth-self.achieveservice.com` (no scraping fragility past one-time WebView bootstrap for sid/csrf)
- **Communal-aware trust filter** — drops phantom collection rounds returned by one of the two schedule lookups for shared-bin properties (resolves the Adelaide St H22 phantom Friday case)
- Cross-lookup dedup by `date|waste|round`
- **Fast path** — reuses saved sid/csrf/cookies for ~25 min before re-bootstrapping
- Parallel schedule POSTs over a shared OkHttp connection pool
- Pull-to-refresh, "last updated" footer, tap row → details bottom sheet
- Per-bin-type notification toggles
- Notification actions: Snooze 30m + Mark out
- GPS-assisted postcode prefill on address capture
- Home-screen widget (Glance)
- Auto-update check against this repo's GitHub Releases

## Build

```powershell
.\gradlew.bat assembleRelease
```

Output: `app/build/outputs/apk/release/PlymouthBins-<version>-release.apk`

Version is sourced from `gradle.properties`:
```
APP_VERSION_CODE=8
APP_VERSION_NAME=1.6
```

### Signing

Release builds need a keystore. Credentials are read from env vars OR `local.properties`
(both gitignored). Example `local.properties`:

```
sdk.dir=C:\\Users\\<you>\\android-sdk
PLYMOUTH_KS_PASS=<password>
PLYMOUTH_KEY_ALIAS=<alias>
PLYMOUTH_KEY_PASS=<password>
```

Keystore file: `release.keystore` in the repo root (gitignored), or override via
`PLYMOUTH_KEYSTORE` env var pointing to an absolute path.

## Install

```powershell
adb uninstall com.plymouthbins.app
adb install app\build\outputs\apk\release\PlymouthBins-1.6-release.apk
```

## Architecture

```
app/src/main/java/com/plymouthbins/app/
  data/
    Constants.kt         endpoint URLs, lookup IDs, tunables
    BinModels.kt         BinCollection(date, waste, status, round); visibleToday()
    BinApi.kt            OkHttp POSTs, premise resolve loop, parallel schedule fetch,
                         mergeAndFilter (communal trust filter), parseRows
    BinBootstrap.kt      hidden WebView session capture (capture, captureFull),
                         single-flight bootstrapMutex
    Prefs.kt             DataStore: address, schedule settings, saved creds w/ TTL,
                         disabledCategories, lastRefreshAtMs
    LocationHelper.kt    last-known-location → reverse geocode → postcode
    Updater.kt           GitHub Releases check + semver compare
    AppLog.kt            ring buffer + StateFlow
    ScheduleCache.kt     disk cache of rows
    AlarmLedger.kt       record of scheduled notifications
    WasteType.kt         pretty(), category(), icon/color helpers
  ui/                    Compose screens: MainScreen, SettingsScreen,
                         AddressCaptureScreen, LogScreen, DebugScreen
  work/
    RefreshWorker.kt     layered fast→full refresh; per-bin-type filter; auto-flag
                         needsRecapture after 3 consecutive empty fetches
    NotificationScheduler.kt   AlarmManager scheduling + per-category filter
    NotificationReceiver.kt    fires + handles Snooze/Mark out actions
    BootReceiver.kt
  widget/
    BinsWidget.kt        Glance home-screen widget
```

## Lookup ID catalog

| ID | Purpose | Notes |
|---|---|---|
| `560d5266e930f` | Address search by postcode | Returns rows of `{uprn, display, flat, house, street, postcode, lat/lng, ward, usrn}` |
| `6936e38f6d376` | Get `collectiveKey` for UPRN | Returns `{collectiveKey: "..."}` for a given UPRN |
| `69f05bb2ad2d6` | Resolve `relatedUPRN` (communal premise) | Empty for private dwellings; non-empty for shared bins |
| `698b9c49a3c13` | Primary schedule lookup | Returns all waste types (Residual, Recycling, Garden, Food) |
| `69fde187c451b` | Secondary schedule lookup | Usually mirrors primary; can return extra phantom rounds on communal premises (filtered out by `mergeAndFilter`) |

## Communal trust filter

For premises where `relatedUPRN != UPRN`, only rounds present in BOTH schedule
lookups are kept. Drops Friday H22 phantoms returned by the secondary lookup
on Adelaide St-style communal blocks.

## CI / Releases

`.github/workflows/`:
- **`build.yml`** — on every push/PR to `main`: builds debug APK, uploads as workflow artifact (14d retention).
- **`release.yml`** — on tag push `v*` (e.g. `git tag v1.7 && git push --tags`): builds signed release APK, creates a GitHub Release, uploads the APK as a release asset, auto-generates release notes.

The in-app `Updater` polls `releases/latest`, compares semver, and (if newer) shows a
banner with a **Download APK** button that opens the release's APK asset URL directly.

### Required GitHub secrets (for `release.yml` to sign the APK)

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.keystore` output |
| `RELEASE_KS_PASS` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASS` | key password |

Generate the keystore base64:
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore"))
```

Then add at: Settings → Secrets and variables → Actions → New repository secret.

If `RELEASE_KEYSTORE_BASE64` is missing the workflow will warn and build unsigned
(which won't install on most devices).

### Cutting a release

```powershell
# bump in gradle.properties
APP_VERSION_CODE=9
APP_VERSION_NAME=1.7

git commit -am "Bump to v1.7"
git tag v1.7
git push && git push --tags
```

Workflow builds + publishes; in-app updater finds it on next launch.

## Distribution variants

The build supports two distribution channels, gated by a single Gradle property:

| Build flag | `BuildConfig.ENABLE_UPDATER` | Update channel |
|---|---|---|
| _(none — default)_ | `true` | `Updater.kt` polls GitHub Releases, banner offers APK download |
| `-PplayStore=true` | `false` | `PlayUpdateHelper` uses Play in-app updates (IMMEDIATE flow) on every `onResume` |

The Play Core in-app updates library is shipped in both variants; on non-Play
installs it resolves to `UPDATE_NOT_AVAILABLE` and is a no-op, so it adds no
behaviour to the GitHub build.

### Building the Play Store AAB

```powershell
.\gradlew.bat bundleRelease -PplayStore=true
# Output: app\build\outputs\bundle\release\app-release.aab
```

### Building a universal APK from the AAB for local testing

```powershell
# One-time
Invoke-WebRequest "https://github.com/google/bundletool/releases/download/1.17.2/bundletool-all-1.17.2.jar" -OutFile bundletool.jar

java -jar bundletool.jar build-apks `
  --bundle=app\build\outputs\bundle\release\app-release.aab `
  --output=app-release.apks `
  --ks=release.keystore `
  --ks-key-alias=$env:PLYMOUTH_KEY_ALIAS `
  --ks-pass=pass:$env:PLYMOUTH_KS_PASS `
  --key-pass=pass:$env:PLYMOUTH_KEY_PASS `
  --mode=universal

Rename-Item app-release.apks app-release.zip
Expand-Archive app-release.zip -DestinationPath app-release-apks
adb install -r app-release-apks\universal.apk
```

### Play Store submission checklist

- Build AAB with `-PplayStore=true` (no GitHub-release polling).
- Enrol upload key in Play App Signing — keep the `release.keystore` SHA-1
  (`keytool -list -v -keystore release.keystore -alias $env:PLYMOUTH_KEY_ALIAS`).
- Privacy policy URL: hosted at [`docs/privacy.md`](docs/privacy.md) — enable
  GitHub Pages → branch `main` folder `/docs` → URL becomes
  `https://damonroberts95.github.io/plymouthBinApp/privacy`.
- Listing copy must include _"unofficial — not affiliated with Plymouth City
  Council"_ to avoid trademark complaints.
- Data safety form: only on-device data, no analytics, no sharing.
- Permissions: `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` need exact-alarm
  justification on the Play Console form (reminders fired at the user's chosen
  time).
- Closed testing with 12+ testers over 14 days before production (Google
  policy since 2023).
- Upload `app/build/outputs/mapping/release/mapping.txt` for symbolicated
  crashes (R8 already minifies).

## License

Personal / private project. Not affiliated with Plymouth City Council.
