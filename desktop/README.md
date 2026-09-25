# photoHostPC

A photo library server for Windows that the existing gpicAlter phone app talks to unmodified. The
phone and the PC are peers: either can hold a library, and a person can run several. This app is
only the server. Browsing is the web UI it serves at `/`, or the phone app.

## Status: milestone 0

Serves a library folder to the phone app over pinned TLS: timeline, thumbnails, originals with Range,
and the phone's backup protocol (so a phone can back up to the PC). Checked by 25 end-to-end tests
that drive the server through the phone's own client stack. **Not yet confirmed with a real phone
scanning the real QR code.**

Not in this milestone: video and HEIC thumbnails (those files index, list and play; their thumbnail
jobs wait as BLOCKED until a decoder ships), importing from other folders, installer, tray icon,
autostart.

## Run

Needs a JDK 21+. Android Studio's bundled one works:

```sh
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew run
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew run --args="--library D:/Photos --port 8080"
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test
```

A window shows the pairing QR code and link. Closing it stops the server.

| Option | Meaning |
|---|---|
| `--library <dir>` | Library folder; remembered. Default `%USERPROFILE%\PhotoHost Library` (not Pictures, which OneDrive often syncs) |
| `--port <n>` | Plain HTTP port; TLS is always port + 363. Remembered. Default 8080 / 8443 |
| `--data <dir>` | Data directory. Default `%LOCALAPPDATA%\PhotoHost` |
| `--headless` | No window; prints the pairing link |
| `--print-endpoints` | Lists network adapters and what would be advertised, then exits |

The data directory holds `config.properties` (port, token, library), `gpic.db`, `thumbs/`, `tls/`,
`staging/` and `photohost.log`. The library folder holds only originals, plus a hidden
`.gpic-library-id` that keeps its identity if the drive letter changes.

Files copied into the library folder are picked up at start-up, or with **Rescan library**.

## Pairing a phone

1. Start the server. Windows Firewall asks whether Java may accept connections; allow **Private**
   networks. The PC's Wi-Fi must be a Private network.
2. On the phone: Settings, Scan a pairing code, then scan the window's QR code.
3. The phone pins the certificate's fingerprint from the code and uses the TLS address.

Pairing replaces whichever library the phone was showing; the app holds one library at a time.

## Relationship to gpicAlter

The server code is **copied** from gpicAlter at commit `d43de56`, package names unchanged, so the
planned extraction into a shared module is a move rather than a merge. Until then, a fix in either
copy must be made in both. Every change from the phone's version is marked `DIVERGES FROM
gpicAlter` or `DESKTOP REIMPLEMENTATION` in the source:

| File | Change |
|---|---|
| `storage/LibraryStore.kt` | `ReadHandle`/`WriteHandle` instead of `ParcelFileDescriptor` (the proposed phone change) |
| `server/RangeResponder.kt` | Positional stream instead of `lseek` |
| `server/UploadService.kt` | `sync()` before the staging copy is deleted |
| `index/LibraryIndexer.kt` | Metadata and blurhash through `MediaProbe` |
| `media/BlurHash.kt` | Takes packed pixels, not a `Bitmap` |
| `media/MetadataExtractor.kt` | metadata-extractor + ImageIO; rules copied; video rotation converted to Android's clockwise convention |
| `media/ThumbnailGenerator.kt` | ImageIO; same sizes, quality and cache layout |
| `media/DesktopMediaProbe.kt` | Blurhash rotated before encoding (the phone's is sideways for EXIF 6/8) |
| `jobs/Handlers.kt` | No decoder for a format means BLOCKED, not failed |
| `server/NetInterfaces.kt` | Skips Hyper-V/VirtualBox/VMware adapters; labels Java-on-Windows names |
| `server/HttpServer.kt` | Web UI from resources; metadata through `MediaProbe` |
| `server/CertStore.kt`, `media/ThumbnailCache.kt` | `java.util.Base64`, `File.usableSpace` |
| `data/db/AppDatabase.kt` | Room's JVM builder with bundled SQLite; migrations on `SQLiteConnection`, same SQL |

Unchanged apart from the `Log` import: `core/`, `data/entity`, `data/dao`, `jobs/JobRunner.kt`,
`server/Auth.kt`, `server/TlsProxy.kt`, and the web UI.

**Database parity** is checked, not assumed: `schemas/.../4.json` is identical to gpicAlter's
`app/schemas/.../4.json`, identity hash `c98416cf5463e8ac8d563a9cdcfa0571`.

## Licences of shipped dependencies

Kotlin, kotlinx, Ktor, Room, androidx.sqlite (Apache-2.0); SQLite (public domain); TwelveMonkeys
(BSD-3); metadata-extractor (Apache-2.0) and xmpcore (BSD); zxing (Apache-2.0); SLF4J (MIT).
The test fixture `VID_rotated.mp4` was generated with ffmpeg; ffmpeg is not a dependency.
