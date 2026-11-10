# Display for Ontime (Fire TV)

A full-screen display app for [Ontime](https://github.com/cpvalente/ontime) on Amazon Fire TV and
Android TV. Point a TV at your Ontime server and it shows any Ontime view (Timer, Backstage,
Cuesheet, Countdown and so on) and keeps it on screen.

This is an independent app for Ontime. It is not made by or affiliated with the Ontime project.

## What it does

- Asks for your Ontime address on first launch, for example `192.168.1.20:4001` or
  `ontime.example.com`. A share link with **Authenticate** ticked works too, and skips the login.
- Keeps the screen on, so the Fire TV screensaver doesn't cover the timer.
- Comes back by itself if Fire OS's "Are you still watching?" prompt sends the TV to the home screen.
  Pressing Home on the remote still leaves normally.
- If the server can't be reached, shows a message and retries every 10 seconds.
- Lets the remote drive Ontime's own menus and settings (see below).
- Saves several sites and switches between them, keeping the same view.

## Using the remote

| Button | Does |
|---|---|
| **Arrows** | Move a highlight between Ontime's buttons, menus and settings. The first press may just reveal Ontime's buttons, which hide when idle. |
| **Select** | Press the highlighted item |
| **Menu (☰)** | Switch site, add or remove a site, or reload |
| **Back** | Close an open Ontime menu or panel; otherwise go back a page. Press twice to exit. |

### Addresses

You can type just `192.168.1.20:4001` or `ontime.example.com`. Local addresses (IP addresses,
`localhost`, `*.local`, names without a dot, or anything with a port number) get `http://`; other
names get `https://`. Type `http://` or `https://` yourself to override it.

A pasted share link such as `http://192.168.1.20:4001/timer?token=…` saves the site as
`http://192.168.1.20:4001/` and opens the link.

On newer Fire TVs, the on-screen keyboard offers a QR code so you can type or paste on your phone.

Share links with a login token give full access to Ontime, so only put them on screens you control.

## Install on a Fire TV (sideload)

1. On the Fire TV: **Settings → My Fire TV → Developer options**: turn on **ADB debugging** and
   **Apps from Unknown Sources** (newer Fire OS: *Install unknown apps*). If Developer options is
   missing, open **Settings → My Fire TV → About** and click the device name 7 times.
2. Find its IP address: **Settings → My Fire TV → About → Network**.
3. From a computer with the Android SDK platform tools (accept the "Allow USB debugging?" prompt on
   the TV the first time):

```bash
adb connect <fire-tv-ip>:5555
```
```bash
adb install -r app-debug.apk
```

### Set it up from a computer

Open a page (saved as the address to show):

```bash
adb shell "am start -n io.github.whiteflash34.ontimedisplay/.MainActivity --es url 'PASTE-LINK-HERE'"
```

Replace the saved sites (`Name|address;Name|address`):

```bash
adb shell "am start -n io.github.whiteflash34.ontimedisplay/.MainActivity --es projects 'Stage|http://192.168.1.20:4001/;Foyer|http://192.168.1.21:4001/'"
```

Keep the single quotes; links contain `&`. Page console messages go to the device log:
`adb logcat -s OntimeTV`.

## Build

Requires the Android SDK and Java 17 or 21 (Android Studio includes both). Open the folder in
Android Studio, or:

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Make your own edition

Create `edition.properties` next to `settings.gradle` (copy `edition.properties.example`) to change
the app without touching the code:

| Setting | Meaning | Default |
|---|---|---|
| `applicationId` | Android app ID. Use a domain you control. Changing it makes a separate app. | `io.github.whiteflash34.ontimedisplay` |
| `appName` | Name in the Fire TV launcher | `Display for Ontime` |
| `defaultSites` | Sites built in, `Name\|address;Name\|address`. With any set, the app skips the first-launch prompt. | empty |
| `allowHttp` | Allow plain `http://` (needed for most local Ontime servers) | `true` |
| `brandingRes` | Folder with your own `drawable-xhdpi/banner.png` (320×180) and `mipmap-*/ic_launcher.png` | `branding/res` |

To publish your build, sign it with your own release key and keep that key safe: every update must
be signed with the same key.

## How it works

The app is a single full-screen WebView (`app/src/main/java/.../MainActivity.java`). After each page
loads it injects `app/src/main/assets/dpad-nav.js`, which moves focus with the remote's arrow keys,
shows a focus ring, keeps focus inside open dialogs, and stops Fire TV's Select key from activating
Ontime's custom buttons twice.

Tested on Fire TV Stick 4K (Fire OS / Android 7.1) and Fire TV Stick 4K Max (Fire OS 7 / Android 9).

## Licence

MIT. See [LICENSE](LICENSE). Ontime itself is a separate project with its own licence.
