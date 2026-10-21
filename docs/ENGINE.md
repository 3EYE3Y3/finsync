# FinSync engine — design (version 0.1)

The engine is a Kotlin Multiplatform module (`engine/`, JVM and Android targets) used by the Android app (`app/`) and a small command line for testing (`cli/`). Package root: `com.eye3y3.finsync`. Common code uses only Kotlin, kotlinx (coroutines, serialization), Ktor client and Okio; anything platform-specific goes behind `expect`/`actual` with JVM and Android `actual`s.

Build and test: `./gradlew :engine:allTests :cli:test` (see the README for the JDK and Android SDK).

## Components

### naming/ — `Naming`
Implements [NAMING.md](NAMING.md) exactly: `safeComponent`, `safeFileName` (lower-cased extension kept whole; an extension is the text after the last `.` when it is 1–6 ASCII letters or digits), `truncateUtf8(s, maxBytes)` (never splits a code point, including surrogate pairs), `safeRelativePath` (splits on `/` and `\`, drops empty, `.` and `..` parts), `tidyPath(TrackMeta)` (the layout table), and `resolveClashes(List<Pair<itemId, path>>)` (case-insensitive, ` (2)` suffixes, stable by item id).

### jellyfin/ — `JellyfinClient`
Takes a Ktor `HttpClient` (the engine is injected, so tests and Android can supply their own) and a `ServerConfig(baseUrl, deviceId, deviceName)`. Every request sends
`Authorization: MediaBrowser Client="FinSync", Device="<name>", DeviceId="<id>", Version="<ENGINE_VERSION>"` plus `, Token="<token>"` once signed in.

| Call | Endpoint |
|---|---|
| Server info | `GET /System/Info/Public` → `ServerName`, `Version`, `Id` |
| Quick Connect available | `GET /QuickConnect/Enabled` → `true`/`false` |
| Quick Connect start | `POST /QuickConnect/Initiate` → `Secret`, `Code` |
| Quick Connect poll | `GET /QuickConnect/Connect?secret=…` → `Authenticated` |
| Quick Connect finish | `POST /Users/AuthenticateWithQuickConnect` `{"Secret":…}` → `AccessToken`, `User` |
| Password sign-in | `POST /Users/AuthenticateByName` `{"Username":…,"Pw":…}` → same |
| Current user | `GET /Users/Me` → `Id`, `Name`, `Policy.EnableContentDownloading`, `Policy.IsAdministrator` |
| Music libraries | `GET /UserViews?userId=…` → views with `CollectionType == "music"` |
| Library folders (admins only; optional) | `GET /Library/VirtualFolders` → `Name`, `CollectionType`, `Locations` |
| Tracks | `GET /Items?userId=…&ParentId=<view>&IncludeItemTypes=Audio&Recursive=true&Fields=Path,MediaSources,Etag,DateCreated&StartIndex=…&Limit=500` → `Items`, `TotalRecordCount` (paged until all fetched) |
| Playlists | `GET /Items?userId=…&IncludeItemTypes=Playlist&Recursive=true`, then `GET /Playlists/{id}/Items?userId=…` |
| Album art | `GET /Items/{albumId}/Images/Primary?format=Jpg` (only when the album's `ImageTags.Primary` exists) |
| Lyrics | `GET /Audio/{id}/Lyrics` → `Lyrics[{Text, Start}]` (`Start` in 100 ns ticks; only when the item has `HasLyrics`) |
| Original file | `GET /Items/{id}/Download`, with `Range: bytes=N-` to resume |

JSON is parsed leniently (`ignoreUnknownKeys`). HTTP errors become a sealed `FinSyncError` with a plain-English message (server unreachable, signed out, download not allowed, …), never a stack trace for the user.

### model — `RemoteTrack`
`id, name, album, albumId, albumArtist, artists, trackNumber, discNumber, discCount?, year, serverPath, size, container, changeKey, runTimeTicks, hasLyrics`. `changeKey = "<size>:<Etag or DateCreated>"`.

### Mirror path
- Normal mode: the server path relative to the library root, made safe with `Naming.safeRelativePath`. The root is, in order: the `--root` option; the longest `VirtualFolders` location containing the path (admins); otherwise the longest common folder of all track paths in that library.
- A track without a path, or outside the root, uses `Naming.tidyPath` instead.
- Then `Naming.resolveClashes` over the whole set.

### manifest/ — `Manifest`
`<dest>/.finsync/manifest.json`, written atomically (temp file, then rename). Records only what FinSync wrote or adopted:
```json
{ "version": 1, "serverId": "…", "userId": "…", "libraryId": "…",
  "files": { "Artist/Album/01 Song.flac": { "itemId": "…", "kind": "track", "size": 123, "changeKey": "…", "adopted": false } } }
```
`kind` is `track`, `art`, `lyrics` or `playlist`. Paths use `/`. A `.finsync/lock` file stops two syncs running on one destination. It holds an owner id and a heartbeat that the running sync refreshes every minute; a lock not refreshed for 5 minutes was left by a sync that was killed and is taken over (a lock without a heartbeat uses the file's time). Only the owner removes it.

### plan/ — `MirrorPlanner`
Pure function: (remote tracks, playlists, manifest, what exists on disk, options) → `SyncPlan`:
- **download**: a track not in the manifest whose path is free, or one whose `changeKey` changed.
- **adopt**: the path already has a file that isn't in the manifest and its size equals the server's. It is recorded as FinSync's (`adopted: true`) without downloading.
- **conflict**: the path has a file that isn't FinSync's and differs in size. Never overwritten; reported.
- **move**: a manifest item id now maps to a different path; renamed locally instead of downloaded.
- **delete**: manifest entries whose item is gone (or now outside the filter).
- **art / lyrics / playlists**: `cover.jpg` per album folder where art exists and no non-FinSync `cover.jpg` is there; `.lrc` next to tracks with lyrics; one `.m3u8` per playlist.
- **Totals**: files and bytes per action, bytes needed, number of names changed by the naming rules.
- **Deletion brake**: tripped when deletes exceed `brakeFraction` (default 0.10) of the manifest's tracks, or the server returned no tracks while the manifest has some. A tripped plan is never executed unless `allowLargeDelete` is set.
- **Filters** (for small test syncs): `maxBytes` (whole albums in artist/album order until the limit), `artists`.

### download/ — `Downloader`
Downloads to `<dest>/.finsync/partial/<itemId>.part`; resumes with `Range` when the part file exists (206 → append; 200 → start again). Then checks the size and that the file starts like audio, by content rather than name (`fLaC`, `ID3` or an MPEG frame sync, `OggS`, `ftyp` at byte 4, `RIFF`…`WAVE`, `FORM`…`AIFF`, `wvpk`, `MAC `, `FRM8`, `DSD `, ASF); a file named as one of those formats that starts like none of them is rejected, other names pass on size alone, and only then moves it into place. Three retries with backoff; 2 downloads at a time by default.

### playlist/ — `Playlists`
`<dest>/<safe name>.m3u8`, UTF-8 without BOM: `#EXTM3U`, then `#EXTINF:<seconds>,<artist> - <title>` and the track's mirror path (relative, `/`). Only tracks present in the mirror.

### reuse/ — `CardMatcher`, `AudioLayouts`, `Integrity`
Music that is already on the card (an older copy of the library, perhaps tagged differently) is reused instead of downloaded again. `AudioLayouts` separates a file's audio from its tags (FLAC metadata blocks and the STREAMINFO MD5; MP3 ID3v2, APE, ID3v1 and Lyrics3, with the duration from a Xing/Info frame). `CardMatcher` matches card files to library tracks by content: duration, then audio length without tags, the FLAC MD5, then 16 KB samples compared with the server's copy through Range requests. Before a match is used, `Integrity` reads the whole card file (FLAC frame CRC-8/CRC-16, MP3 frame chain); a damaged file is left where it is and the track is downloaded. A reused file is either moved and its tags overwritten in place, or rebuilt from the server's header, the card's audio and the server's trailer; either way it ends up byte-identical to the library's file. Other copies of a track (spares) and music that isn't in the library are listed in `.finsync/card.json`; spares are removed only when the user asks, and only while the mirror holds the track.

### sync/ — `SyncEngine`
`plan()` and `execute(plan, listener)`. Checks free space first (expect/actual `freeBytes`; needs the plan's bytes plus 1% and 200 MB). Order: moves, downloads, art, lyrics, playlists, deletes last (and only files listed in the manifest). The manifest is saved after every completed file so an interrupted sync loses nothing. Progress is reported in plain words.

## Command line (for testing)
```
finsync login  --server http://192.168.1.10:8096 [--wait 300]   # Quick Connect: prints the code
finsync status
finsync plan   --dest DIR [--root PATH] [--list] [--json FILE]
finsync sync   --dest DIR [--max-bytes 5G] [--artist NAME]… [--allow-large-delete] [--concurrency 2]
```
Credentials (`server`, `deviceId`, `userId`, `token`) live in `~/.config/finsync/credentials.json`, mode 600, never in the repository. Exit codes: 0 ok, 1 error, 2 deletion brake, 3 not signed in.

## Tests
Unit tests for naming, planner, manifest and playlists in `commonTest` (Okio `FakeFileSystem`). Integration tests in `jvmTest` against a **fake Jellyfin server** (Ktor CIO, in-memory library) that implements the endpoints above, including Range requests, a switch that cuts a download part-way (to test resume), Quick Connect, and a user without download permission.
