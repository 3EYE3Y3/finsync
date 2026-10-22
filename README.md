# FinSync

**Your Jellyfin music library on your phone or music player's SD card: a real copy, not a cache.**

FinSync copies the music from your own [Jellyfin](https://jellyfin.org) server into a folder you choose, typically on an SD card, as plain files any player app can play: original quality, album covers, synced lyrics (`.lrc`) and playlists (`.m3u8`). After that it keeps the copy in step with the server, adding, updating and removing tracks.

It was made for dedicated music players (Android-based DAPs such as HiBy or FiiO) and phones with a large card, where streaming isn't the point.

- **Exact copies.** The server's original files, byte for byte; FLAC stays FLAC. Names are made safe for FAT32/exFAT cards (see [docs/NAMING.md](docs/NAMING.md)).
- **Reuses music already on the card.** If the card holds an older copy of your library, FinSync recognises the same recordings by their audio (not their names or tags), checks every file for damage, and moves them into place instead of downloading hundreds of gigabytes again. Spare copies are only removed when you confirm; music that isn't in your library is never touched.
- **Safe by design.** It only deletes files it wrote itself, stops if the server suddenly seems to have lost much of the library, and resumes interrupted downloads.
- **Long syncs keep going.** A first sync can take hours: it runs in the background with a progress notification and a Stop button, and can repeat on its own on Wi-Fi while charging.
- **Private.** It talks only to your server. No accounts, analytics or ads.

## Install

FinSync is in beta (Android 8.0 or newer).

- **Google Play (beta):** join the [finsync-beta Google Group](https://groups.google.com/g/finsync-beta), then become a tester on [Google Play](https://play.google.com/apps/testing/com.eye3y3.finsync) and install it from there. Updates come through Play.
- **APK:** download `finsync-<version>.apk` from the [latest release](https://github.com/3EYE3Y3/finsync/releases/latest). The app tells you when a newer release is out. Each release lists the APK's SHA-256. Every GitHub APK is signed with the same certificate, SHA-256 fingerprint `95:88:C0:9F:E2:B4:4C:DC:FF:CB:6E:94:D9:9A:13:3B:0A:13:A8:7E:D1:F4:4F:71:A9:FF:2D:78:CC:5D:A2:74`: check it with `apksigner verify --print-certs finsync-<version>.apk`, or let [Obtainium](https://obtainium.imranr.dev/) or [AppVerifier](https://github.com/soupslurpr/AppVerifier) do it.
- **[Obtainium](https://obtainium.imranr.dev/):** add `https://github.com/3EYE3Y3/finsync` for automatic updates from GitHub Releases.

The Play version and the GitHub APK are signed with different keys, so one can't update the other. To switch, uninstall first (your synced music stays on the card; you'll need to sign in and choose the folder again).

## Getting started

1. **Find your server:** type its address (e.g. `http://192.168.1.10:8096`) and approve the Quick Connect code in Jellyfin, or sign in with your password. Your Jellyfin user needs permission to download.
2. **Choose a folder:** pick the SD card (or a folder on it). FinSync keeps its copy in a `Jellyfin` folder inside.
3. **Check and sync:** FinSync shows what it will download, reuse and remove before anything happens.

**Choose what to sync:** the whole library (untick artists, albums or whole libraries you don't want), or only what you pick (artists, albums and playlists). Music you untick is removed from FinSync's folder at the next sync, after asking if it's a lot; nothing outside that folder is touched.

**No server yet?** Tap *No server yet? Try it with sample music* on the first screen. FinSync then syncs a small built-in sample library (CC0, [docs/DEMO-ASSETS.md](docs/DEMO-ASSETS.md)) into its own folder; *Use my own server* removes it again.

## Feedback and help

Use **Send feedback** in the app, or [open an issue](https://github.com/3EYE3Y3/finsync/issues/new/choose). Security problems: see [SECURITY.md](SECURITY.md).

## Building

You need JDK 21 and the Android SDK (platform 36, build-tools 35.0.0); set `ANDROID_HOME` (or `sdk.dir` in `local.properties`). Then:

```
./gradlew :app:assemblePlayDebug          # or :app:assembleGithubDebug
./gradlew :engine:allTests :cli:test :app:testPlayDebugUnitTest
```

The debug APK is in `app/build/outputs/apk/`. How the pieces fit together: [docs/ENGINE.md](docs/ENGINE.md) (sync engine), [docs/ANDROID.md](docs/ANDROID.md) (app, builds, releases), [docs/NAMING.md](docs/NAMING.md) (file names). Contributions: [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

FinSync is free software: GPL-3.0-or-later (see [LICENSE](LICENSE)).

FinSync is an independent project. It is not affiliated with or endorsed by the Jellyfin project; "Jellyfin" is used only to say which server it works with.
