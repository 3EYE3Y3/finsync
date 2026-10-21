# Android app

`app/`: native Kotlin + Jetpack Compose, minSdk 26 (Android 8.0), target 36. It uses the engine module (`engine/`) unchanged.

**Application id `com.eye3y3.finsync`** (permanent: it is the app's identity on Google Play). The app's code is in `com.eye3y3.finsync.app`; the engine's packages are `com.eye3y3.finsync.*`.

## Build
See the README for the JDK and Android SDK. Then:

```
./gradlew :app:assemblePlayDebug        # app/build/outputs/apk/play/debug/app-play-debug.apk
./gradlew :app:assembleGithubDebug      # the same app with the GitHub update check
./gradlew :engine:allTests :cli:test :app:testPlayDebugUnitTest :app:testGithubDebugUnitTest
```

## Two builds: `play` and `github`
One code base, two product flavours with the same application id:

| | `play` | `github` |
|---|---|---|
| Delivered as | App bundle on Google Play (beta: closed testing) | APK on GitHub Releases (and Obtainium) |
| Signed with | the Play upload key (Play App Signing re-signs it) | the direct-download key |
| Updates | Google Play In-App Updates (flexible): a card offers the update, it downloads in the background, one tap restarts into it | once a day the app reads the latest GitHub release (its `version.json` asset holds the `versionCode`); a newer one shows a card linking to the APK |
| Code | `app/src/play/` | `app/src/github/` |

The two are signed with different keys, so Android won't let one update the other: to switch, uninstall first. The `play` build never contacts GitHub; the `github` build doesn't include Play's update library.

## Releases
`app/version.properties` holds `versionCode` (shared by both builds, bumped by the scripts) and `versionName` (set by hand). Release keys live outside the repository in `~/.config/finsync/` (`signing.properties` for the upload key, `direct-signing.properties` for the direct key: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`; mode 600). Without them, release builds are debug-signed and the scripts refuse to publish.

- `scripts/release-play.sh`: tests, then the signed, R8-minified bundle and its mapping file in `builds/play/`. Upload both in Play Console.
- `scripts/release-github.sh`: tests, then `builds/github/v<versionName>/` with `finsync-<versionName>.apk`, its `.sha256`, `version.json`, `release-notes.md` and the R8 mapping (kept, not published). It prints the `gh release create` command that publishes it. Set a new `versionName` before each GitHub release (the tag is `v<versionName>`).
- Optional: `FINSYNC_COPY_TO=<folder>` in `~/.config/finsync/release.env` also copies the results there.
- Commit `app/version.properties` after each release.

## Feedback
**Send feedback** on the home screen opens the feedback page with only the app version, the build (`play`/`github`), the Android version and the device model in the address: never the server, user name or anything about the library.

## Permissions
| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Talk to the user's own Jellyfin server; scheduled syncs wait for unmetered Wi-Fi |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | The sync runs as a user-started foreground service of type `dataSync` (copying the library to the SD card, which can take hours the first time); WorkManager's foreground service is also `dataSync` |
| `POST_NOTIFICATIONS` | The ongoing progress notification of that service (asked at the first sync) |
| `WAKE_LOCK` | Keep the CPU awake while a sync runs with the screen off (and WorkManager) |
| `RECEIVE_BOOT_COMPLETED` | Added by WorkManager to keep the scheduled sync after a restart |
| `com.eye3y3.finsync.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added by AndroidX for its own internal receivers; not a user permission |

Also: `usesCleartextTraffic="true"` because home Jellyfin servers are usually plain `http://` on the home network or over a VPN. No storage permission: the SD card folder is granted through the system folder picker (Storage Access Framework).

## Demo mode ("Try it with sample music")
For people without a server yet, and for app-store reviewers (Jellyfin's public demo servers don't allow downloads). A small text link under **Connect** on the first screen; it disappears for good once a real server has been used. It runs a Jellyfin inside the app (`demo/DemoServer`, Ktor MockEngine, no network) serving the bundled CC0 sample library ([DEMO-ASSETS.md](DEMO-ASSETS.md)); everything else is the real flow (folder picker, check, sync, notification with Stop, covers, lyrics, a playlist, SD-safe renaming). Whole-track downloads are paced to about 2 s each, one at a time (about 30 s for the 13 samples), so progress, the notification and Stop can be seen; real servers are never paced. Samples go to their own folder, `FinSync sample music`; demo mode never scans other music on the card and never schedules syncs. The home screen shows "Sample library" and **Use my own server**, which removes the sample files (only those the sample syncs wrote) and starts the real setup.

## How it works
- **Card access:** the user grants one folder with the system folder picker (SAF). `SafFileSystem` is an Okio file system over it, so the engine runs as on a desktop. FinSync's mirror is the `Jellyfin` folder inside the granted folder.
- **Music already on the card:** everything else in the granted folder (except `Android/`, hidden folders and `Jellyfin/`) is matched with the library by content (see ENGINE.md, "reuse"). Matches are moved into `Jellyfin/` and get the library's tags, byte for byte, instead of being downloaded. Each card file is read in full first (FLAC frame CRCs, MP3 frame chain); damaged ones stay put and the track is downloaded. Extra copies are removed only from the "Extra copies" screen after confirming, and only while the mirror holds the track. Files not in the library are listed and never touched.
- **Long syncs:** a foreground service (dataSync) with wake and Wi-Fi locks. Android 15+ allows such a service 6 hours a day; when that runs out the sync stops cleanly and the next one resumes.
- **Scheduled syncs:** WorkManager, every 12 hours on unmetered Wi-Fi while charging (switch on the home screen, on by default).
- **Logs:** `adb logcat -s FinSync` shows progress and errors.

## First launch
1. **Find your server:** type the server address (e.g. `http://192.168.1.10:8096`), tap **Connect**, approve the Quick Connect code in Jellyfin (Settings → Quick Connect on a signed-in device). Or **Use a password instead**.
2. **Choose where the music goes:** **Choose folder** → pick the SD card (or its Music folder if Android won't allow the card itself) → **Use this folder** → **Allow** → **Continue**.
3. **Check and go:** wait while it looks at the library and the card, read the summary (tracks reused from the card, to download, extra copies, not in the library, space), tap **Sync** and allow notifications.
