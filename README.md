# PhotoHost

**Your own photo library, on your own phone or PC.** Back up your phone's photos and videos to a
library you run yourself, browse it from your other phones and any web browser, and reach it from
anywhere through Tailscale. No cloud account, no subscription, nothing leaves your devices.

<!-- Screenshots go here: the phone's library grid, the web page, and the Windows window.
     Planned location: docs/screenshots/. -->

> **Status: 0.9.0, early but working.** PhotoHost is used daily by its author on Android phones and a
> Windows PC. Expect rough edges, and keep a second copy of anything irreplaceable.

## What it does

A **library** is a folder of your photos plus an index, served by a phone or a Windows PC. You can
run several, for example one on an old phone at home and one on your PC, and the app switches
between them.

**On Android** (Android 10 or later)
- Hosts a library on the phone itself, in its own storage or on a USB drive.
- Browses libraries on other phones and PCs: a fast timeline grid, day headers, a month rail and a
  drag handle to cross years at once, favourites, trash, HEIC and video.
- **Backs up** the camera roll automatically (chosen folders, Wi-Fi only if you like), or photos
  you pick. Files are matched by content, so nothing is uploaded twice, and interrupted uploads
  resume.
- **Frees up space** by moving photos the library holds safely to the phone's trash. It shows every
  photo first, keeps the last 30 days by default, and checks with the library at that moment.
- **Shares** photos and videos to any app, one or a whole selection, in full quality.

**On Windows** (Windows 10 or 11, 64-bit)
- Serves a library folder of your choice, from the system tray, optionally at sign-in.
- Imports folders or picked files into the library, copying and verifying each one.
- Thumbnails for HEIC, AVIF, RAW and videos, through Windows' own codecs, with ffmpeg as a fallback.

**In any browser**, on any library
- The same timeline, a full-screen viewer with zoom, and download or share of originals.
- **Upload** by button or by dragging files and folders onto the page, from any device, including
  iPhones and Macs.

## Install

Downloads are on the [Releases](../../releases) page.

- **Android:** install the APK. Google Play is planned.
- **Windows:** run the installer. It is not code-signed yet, so Windows warns about an unknown
  publisher (choose *More info*, then *Run anyway*), and on a PC with **Smart App Control** turned on
  it will not run at all. In that case, build it from source (below). A signed release and the
  Microsoft Store are planned.

## Getting started

1. **Pick the device that keeps the photos.** On a phone: open PhotoHost and choose *Keep the photos
   on this phone*, then *Start serving*. On a PC: install and run PhotoHost, then choose the library
   folder.
2. **Pair your other devices.** On the device that keeps the photos, open the pairing code (the
   phone's Server tab, or the PC's window). On another phone, choose *View photos kept on another
   phone or PC* and scan it. To open the library in a browser, scan the same code with the phone's
   camera, or copy its link.
3. **Back up.** On each phone, Settings → *Back up to* chooses the library, and *Folders to back
   up* chooses what goes. *Back up now* starts straight away.

**Away from home:** install [Tailscale](https://tailscale.com) on the device that keeps the photos
and on the devices that view them, signed in to the same account. The pairing code already
includes the Tailscale address, and the app switches between home Wi-Fi and Tailscale by itself.

## Security and privacy

- Every request needs the library's token, which only a pairing code carries.
- Traffic is encrypted, with the library's certificate pinned by the app.
- Photos never leave your devices; there is no PhotoHost server, account or analytics.

Details are in [`android/README.md`](android/README.md#security). To report a vulnerability, see
[`SECURITY.md`](SECURITY.md).

## Building from source

| Folder     | What it is                                                                 |
|------------|----------------------------------------------------------------------------|
| `android/` | The phone app: a library server, the browsing and backup client, pairing. |
| `desktop/` | The Windows library server, with a tray icon, importer and installer.     |
| `web/`     | The web page both servers serve. One copy, built into both.                |

They are two separate Gradle builds, so working on one never needs the other's toolchain. Both
need a JDK 17 or newer to run Gradle (Android Studio's bundled one works).

```
android/gradlew -p android assembleDebug      # the phone app, needs the Android SDK
desktop/gradlew -p desktop test               # the desktop server's tests
desktop/gradlew -p desktop packageExe         # desktop/build/package/PhotoHost/PhotoHost.exe
desktop/gradlew -p desktop packageInstaller   # the installer, needs Inno Setup 6
```

On a PC with Smart App Control on, a freshly built `PhotoHost.exe` is blocked;
`desktop/packaging/dev-launcher.ps1` makes a Start-menu shortcut that runs the build through its
signed Java runtime instead. See [`android/README.md`](android/README.md),
[`desktop/README.md`](desktop/README.md) and [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Licence

Copyright (C) 2026 Akash Verma

PhotoHost is free software: you can redistribute it and/or modify it under the terms of the GNU
Affero General Public License as published by the Free Software Foundation, version 3 of the
License. It is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
[`LICENSE`](LICENSE) for the full text.

In short: you may use, study, change and share PhotoHost, including commercially. If you share it,
changed or not, you must share its source under the same licence. If you change it and let others
use your version over a network, you must offer them its source too.

The licence covers the code, not the name. "PhotoHost" and its icon identify this project; please
give a modified version its own name and icon.
