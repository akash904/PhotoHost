# PhotoHost for Android

A self-hosted photo library that **runs on an Android phone**. One app, two independent jobs:

- **Serve** — host the library, index it, and expose it over HTTP to your LAN or Tailscale.
- **Back up** — send this phone's camera roll to a library hosted somewhere else.

A phone can do either, both, or neither. Browsing always speaks the HTTP API, pointed at
`127.0.0.1` when the phone is its own server, so there is exactly one client code path.

> **This is not a backup.** One phone plus one drive is RAID 0 with extra steps. Do not delete
> originals from a phone or from cloud storage until offsite replication exists.

## Why a native app rather than Immich in Termux

- Android mounts USB volumes under `/mnt/media_rw/…`, which needs the `WRITE_MEDIA_STORAGE`
  signature permission. **Termux cannot see an OTG drive at all** without root. Only the Storage
  Access Framework reaches it, and only from an app.
- Docker does not run on stock Android, so Immich's supported deployment is out regardless.
- Only a foreground service survives Android's background limits and OEM battery killers.

## What works today

| Area | State |
|---|---|
| Storage abstraction | App storage, or a chosen folder on the phone or a USB-OTG drive via SAF, behind one interface; resumable, verified moves between them |
| Index | Room, SHA-256 identity, EXIF/video metadata, blurhash, job queue |
| HTTP server | Ktor CIO in a `specialUse` foreground service, hand-rolled Range support |
| Web UI | Justified grid, sticky date headers, month scrubber, viewer, select + delete, trash |
| Native UI | Same grid in Compose, full-screen viewer with zoom and video, trash, settings |
| Backup client | MediaStore scan, folder selection, per-photo picking, resumable upload |
| Pairing | QR code; one code works for both the app and any browser |

Not built yet: albums, search, face clustering, thumbnail cache eviction, offsite replication.

## Architecture

```
core/      hashing, dispatchers, preferences
storage/   LibraryStore: InternalStore | SafStore (resolution ladder)
data/      Room entities, DAOs, migrations
index/     hash → dedupe → metadata → index
media/     blurhash, EXIF/video extraction, thumbnails
jobs/      durable queue, runner, handlers
server/    Ktor routes, auth, Range streaming, uploads
backup/    MediaStore reader, backup engine, WorkManager worker
net/       the HTTP client the UI uses
ui/        Compose screens
```

Three decisions everything else leans on:

**Identity is the content hash.** The same photo from two phones is one asset, re-uploading is
free, a file that moves is still the same asset, and the library is rebuildable from bytes alone.

**The durable key is (volume, relative path), never a document URI.** ExternalStorageProvider
document ids are literally `"<volumeSerial>:<pathFromVolumeRoot>"`, so a brand-new SAF grant after
a replug resolves every asset with no directory walking.

**The timeline is keyset-paginated.** `WHERE captured_at < ? OR (captured_at = ? AND id < ?)`,
served entirely by `INDEX(captured_at, id)`. Scrolling to 2019 costs what the first page costs.

## Build

Requires Android Studio (bundles a JDK and the SDK) and **SDK Platform 37**.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Room schemas are committed under `app/schemas/`. Migrations are hand-written against them;
`fallbackToDestructiveMigration` is never used, because losing the database means re-hashing and
re-thumbnailing an entire library.

## Using it

**Serve:** Settings → Server → Start. Then Show pairing code.

**Choose where photos are kept:** Server → Storage, with the server stopped. App storage is
deleted if the app is uninstalled; a folder you choose is not. On the phone that must be an empty
folder (create one such as `Pictures/PhotoHost` from the picker), and it gets a `.nomedia` file so
library photos do not appear a second time in the gallery. Switching offers to move the library:
each file is copied, read back and checked against its hash before the original is deleted, and an
interrupted move resumes where it stopped.

**Connect another device:** install the same APK → Settings → Scan a pairing code. Any phone
camera works too — the QR is an ordinary `http://host:port/pair?c=<token>` URL, which signs a
browser in instead.

**Back up:** Settings → Back up → choose Folders, then Automatic backup. Or Pick photos to send a
specific selection once.

## Security

- **Every request needs the library's token.** It travels in the pairing code. The app sends it as a
  bearer token; a browser gets it once through the pairing link and then holds it as an `HttpOnly`
  cookie, so it never appears in a URL, a log or a `Referer` header. Repeated wrong tokens from one
  address are throttled.
- **Encrypted, with the certificate pinned.** Each library serves TLS on its port + 363 (8443 by
  default) with its own self-signed certificate. The pairing code carries the certificate's SHA-256
  fingerprint, and the app trusts that certificate and nothing else, so no certificate authority is
  involved and a man in the middle is refused. The app tries encrypted addresses first and uses
  plain HTTP only when none answers. Browsers warn about the certificate once.
- **Plain HTTP on 8080 remains** for browsers on the local network and as a fallback. It carries the
  token and photos unencrypted, which is why it should only ever be reachable on your own network.
- **Only sensible addresses are advertised:** Wi-Fi (including the phone's own hotspot), Ethernet
  and Tailscale. Mobile-data and other VPN addresses are never offered.
- **Away from home, use Tailscale.** It encrypts end to end and exposes nothing to the internet. The
  optional *Remote access* setting instead asks the router (UPnP, PCP or NAT-PMP) to open the
  encrypted port only. **Never forward the plain port 8080.**
- **Requests cannot leave the library:** file paths are confined to the library folder.

## Licence

PhotoHost is licensed under the GNU Affero General Public License v3.0; see `../LICENSE` and the
root README.
