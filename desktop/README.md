# PhotoHost for Windows

A photo library server for Windows that the PhotoHost phone app talks to unmodified. The
phone and the PC are peers: either can hold a library, and a person can run several. This app is
only the server. Browsing is the web page it serves at `/` (from `../web`), or the phone app.

## Status

Serves a library folder to the phone app over pinned TLS: timeline, thumbnails (including HEIC,
AVIF and RAW through Windows' own codecs, and video frames, with ffmpeg as a fallback), originals with
Range, and the phone's backup protocol. Imports folders or chosen files from the PC. Runs from the
tray, optionally at sign-in, one instance at a time, and has an installer. Checked end to end with
the real phone app.

Not yet: code signing (Smart App Control blocks the unsigned installer), MSIX and the Microsoft Store.

## Run the app

Double-click `PhotoHost.exe` in the `PhotoHost` folder built by `gradlew packageExe`
(`build\package\PhotoHost`). The folder carries its own Java runtime (about 75 MB), so nothing needs
installing. It can be copied or zipped anywhere, but the exe must stay next to its `app` and
`runtime` folders.

The window shows the pairing QR code and link. **Change library folder...** picks the folder to
serve. Each library folder gets its own index, and paired phones stay paired across a change.
**Open in browser** opens the web UI on this PC. Closing the window hides PhotoHost to the tray;
**Quit PhotoHost** (or Quit on the tray icon) stops the server.

```sh
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew packageExe
```

The first `packageExe` downloads a Temurin 25 JDK into Gradle's cache: Android Studio's bundled JDK
compiles fine but has no `jpackage`.

## Run from source

Needs a JDK 21+. Android Studio's bundled one works:

```sh
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew run
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew run --args="--library D:/Photos --port 8080"
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew test
```

| Option | Meaning |
|---|---|
| `--library <dir>` | Library folder; remembered. Default `%USERPROFILE%\PhotoHost Library` (not Pictures, which OneDrive often syncs) |
| `--port <n>` | Plain HTTP port; TLS is always port + 363. Remembered. Default 8080 / 8443 |
| `--data <dir>` | Data directory. Default `%LOCALAPPDATA%\PhotoHost` |
| `--headless` | No window; prints the pairing link |
| `--print-endpoints` | Lists network adapters and what would be advertised, then exits |

The data directory holds `config.properties` (port, token, library), `tls/`, `photohost.log`, and
`libraries/<library-id>/` with each library's `photohost.db`, `thumbs/` and `staging/`. The library folder holds only originals, plus a hidden
`.photohost-library-id` that keeps its identity if the drive letter changes.

Files copied into the library folder are picked up at start-up, or with **Rescan library**.

## Pairing a phone

1. Start the server. Windows Firewall asks whether PhotoHost may accept connections; allow it. If
   that was cancelled, the window says phones are blocked and offers **Allow through firewall...**.
2. On the phone: Settings, Scan a pairing code, then scan the window's QR code.
3. The phone pins the certificate's fingerprint from the code and uses the TLS address.

Pairing adds the PC as one more library in the phone app; the phone's own library, if it has one,
stays. Only Wi-Fi, Ethernet and Tailscale addresses are advertised.

## Relationship to the phone app

The server code is **copied** from the phone app (`../android`) at its commit `d43de56`, with the
same package names, so a later extraction into a shared module is a move rather than a merge. Until
then, a fix in either copy must be made in both. The web page is already shared: one copy in
`../web`. Every change from the phone's version is marked `DIVERGES FROM the phone app` or
`DESKTOP REIMPLEMENTATION` in the source:

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
`server/Auth.kt`, `server/TlsProxy.kt`.

**Database parity** is checked, not assumed: `schemas/.../4.json` is identical to
`../android/app/schemas/.../4.json`, identity hash `c98416cf5463e8ac8d563a9cdcfa0571`.

## Licence

PhotoHost is licensed under the GNU Affero General Public License v3.0; see `../LICENSE` and the
root README.

## Licences of shipped dependencies

Kotlin, kotlinx, Ktor, Room, androidx.sqlite (Apache-2.0); SQLite (public domain); TwelveMonkeys
(BSD-3); metadata-extractor (Apache-2.0) and xmpcore (BSD); zxing (Apache-2.0); SLF4J (MIT);
JNA (Apache-2.0, or LGPL-2.1 at the user's choice).
The test fixture `VID_rotated.mp4` was generated with ffmpeg; ffmpeg is not a dependency.
