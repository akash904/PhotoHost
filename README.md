# PhotoHost

Your photo library, kept on your own phone or PC and reachable from your other devices: over
your Wi-Fi at home, and over Tailscale when you are away. No cloud account.

A library can live on a phone or on a Windows PC. You can run several. The phone app browses and
backs up to any of them, and every library also serves the same web page to any browser.

## What is where

| Folder     | What it is                                                                 |
|------------|----------------------------------------------------------------------------|
| `android/` | The phone app: a library server, the browsing and backup client, pairing. |
| `desktop/` | The Windows library server, with a tray icon, importer and installer.     |
| `web/`     | The web page both servers serve. One copy, built into both.                |

They are two separate Gradle builds, so working on one never needs the other's toolchain.

## Building

Both builds need a JDK 17 or newer to run Gradle (Android Studio's bundled one works).

```
android/gradlew -p android assembleDebug      # the phone app, needs the Android SDK
desktop/gradlew -p desktop test               # the desktop server's tests
desktop/gradlew -p desktop packageExe         # desktop/build/package/PhotoHost/PhotoHost.exe
desktop/gradlew -p desktop packageInstaller   # the installer, needs Inno Setup 6
```

See `android/README.md` and `desktop/README.md` for details.

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
