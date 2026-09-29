# PhotoHost privacy policy

*Last updated: 29 September 2026*

PhotoHost is open-source software for keeping your photo library on your own devices. It is made
by Akash Verma. There is no PhotoHost company server, account, advertising or analytics: the
developer receives nothing from your use of the app.

## What PhotoHost does with your data

- **Your photos and videos.** The Android app reads your photos and videos to back them up, to show
  them, and to free up space if you ask it to. It sends them only to the libraries you pair it
  with, which are your own devices: a phone or a PC running PhotoHost. A library stores them where
  you choose: in the phone's or PC's storage, or on a drive you attach.
- **Pairing information.** Each library you pair with is remembered on your device: its addresses,
  its access token and its certificate fingerprint. These stay on your device.
- **Where things happen.** Photos, thumbnails and the library index stay on the devices that make up
  your libraries. Nothing is uploaded to the developer or to any cloud service by PhotoHost.
- **Pairing codes.** The camera reads a pairing code on the phone itself. The camera image is not
  stored or sent anywhere.

## Networks and third parties you choose

- **Tailscale.** If you use Tailscale to reach a library away from home, that traffic goes through
  Tailscale's network under Tailscale's own privacy policy. PhotoHost's traffic is still encrypted
  end to end with the library's own certificate.
- **Remote access by router.** If you turn on *Remote access*, the app asks your router to open the
  library's encrypted port to the internet. Anyone who reaches it still needs the library's token.
- **Sharing.** When you share a photo, it goes to the app you choose, under that app's policy.

## Permissions the Android app asks for

| Permission | Why |
|---|---|
| Photos and videos | To back up, show and, when you ask, free up space. |
| Camera | Only to scan pairing codes. |
| Notifications | To show that a library is serving or a backup is running. |
| Network access and Wi-Fi state | To serve and reach libraries on your network. |
| Run in the foreground, start at boot | To keep a library served and backups running. |

## Your control

Uninstalling the app removes its data from the phone. A library's photos stay in the folder or
drive you chose, where you can keep, move or delete them. Removing a paired library from the app
forgets its token.

## Children

PhotoHost is not directed at children and collects no information from anyone.

## Changes and contact

Changes to this policy are published in this file, in the project's public repository. Questions:
open an issue at the project's GitHub repository.
