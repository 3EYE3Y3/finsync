# Naming rules

FinSync's engine (`engine/.../naming/Naming.kt`) follows these rules; `NamingTest` pins them. They are chosen so that a [beets](https://beets.io) library can produce exactly the same paths (beets equivalents are noted below), which lets a server library tidied by beets and the card copy agree.

Names stay in their original script (no transliteration to ASCII: `asciify_paths: no`).

## Folder layout

| Kind | Path |
|---|---|
| Album | `Album Artist/Album (Year)/NN Title.ext` |
| Multi-disc album | `Album Artist/Album (Year)/DD-NN Title.ext` |
| Compilation | `Various Artists/Album (Year)/NN Artist - Title.ext` |
| Single track (no album) | `Artist/Non-Album/Title.ext` |

- `NN` and `DD` are two-digit, zero-padded track and disc numbers. The disc prefix appears only when the album's tracks span more than one disc (highest disc number > 1), so a CD+DVD release or a single-CD rip gets plain `NN`. (beets: an inline field such as `multidisc`.)
- ` (Year)` is left out when the year is unknown.
- Two different albums with the same artist and name get a distinguishing suffix (beets `%aunique{}`, e.g. ` [2009]`).
- In FinSync's normal mirror mode the card follows the server's own folder layout; these rules then only make each path component safe. The layout above is used for tracks whose server path is unknown or outside the library folder.

## Making a path component safe

Applied to every component (each folder name and the file name without its extension), in this order:

1. `/` and `\` → `_`
2. Control characters U+0000–U+001F → removed
3. `< > : " ? * |` → `_`
4. Leading whitespace → removed
5. A leading `.` → `_` (no hidden files; checked after step 4 so ` .x` becomes `_x`)
6. Trailing dots and whitespace → removed
7. An empty result → `_`

## Length

- Each component is at most **100 bytes** of UTF-8. A file name's limit includes its extension, which is kept whole; the name before it is shortened.
- Shortening never splits a character; afterwards steps 1–7 run again (shortening can leave a trailing space).
- That is well inside exFAT's and FAT32's 255-character limit per name. A whole `Artist/Album/Track` path stays under about 300 bytes, which Android, Windows (long paths are rare at 3 levels plus a short card prefix) and Linux all accept.

## Extensions

The extension is lower-cased (`.FLAC` → `.flac`).

## FinSync only (beets can't do this)

- **Case-insensitive clashes.** FAT32 and exFAT ignore case, so `ABBA/` and `Abba/` are the same folder. FinSync compares paths case-insensitively and, on a clash between different server items, adds ` (2)`, ` (3)` … before the extension of the later item (sorted by Jellyfin item id, so the result is stable).
- **Playlists** use exactly the same safe paths, relative to the playlist file at the root of the FinSync folder, with `/` separators.
