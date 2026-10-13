# Cacophonics

*Many radios, one phone.*

Listen to several **Icecast2 streams** and **OpenWebRX+ receivers** at the same time on Android.
Each source has its own panel showing what it is playing (your radio's memory channel from the
stream's MP3/ICY tag, or the frequency, mode and bookmark name from a receiver), its own
volume slider and its own mute button. The tag is sent to Bluetooth and car stereos as
"N: tag", and the app works with Android Auto but does not need it.

It was written as a replacement for XiiaLive Pro, which stopped running on current Android, and
was inspired by the work of Bob Aune (ProScan, https://www.proscan.org) and the Icecast2
project (https://icecast.org).

**Android 5.0 (API 21) and newer.** Portrait shows one column of panels; landscape shows two
columns. There are **six** source slots; the screen scrolls if they do not all fit.

## Features

- Up to six sources playing at once, any mix of Icecast2 and OpenWebRX+.
- **Per panel:** stream number, name, volume slider, mute button, and the current tag.
- A panel **turns light grey** while sound is actually being received, and goes back to dark
  when it stops. A muted source shows all its text in red.
- **Icecast2:** the MP3/ICY tag is shown large (wraps over up to three lines, and the last line
  scrolls if it is too long).
- **Tag lockout (Icecast only):** tap the tag icon to silence the tag showing now. The icon is
  steady green while a tag is showing, flashes green while some tags on that stream are locked, and
  flashes red while a locked tag is on air. Tap again to unlock; press and hold to clear the
  stream's list. Locked tags are forgotten when the streams stop.
- **OpenWebRX+:** native client (no browser). Choose the receiver's profile from a list, then a
  mode (analog and digital) and a frequency. The panel shows the frequency, mode, the name of a
  matching bookmark, and call metadata for digital voice. Analog modes have a **squelch slider**
  (left = open, right = closed; it starts fully closed so nothing blasts out, and you slide it left to the level you want). Digital modes do not use squelch.
- **Receiver check at start:** before an OpenWebRX+ stream starts, the app asks the receiver
  whether the saved profile still exists and is still named the same (the name carries the
  range). A stream that fails the check does not start and says why. Two streams on the same SDR
  dongle must use the same profile; streams on different dongles are independent.
- **Bluetooth / car:** the display shows `N: tag` for the stream that changed most recently and is
  not muted; the "artist" line lists the other audible streams. Next / Previous on the stereo cycles
  the stream shown; Play / Pause mutes or unmutes it.
- **When Bluetooth or wired audio disconnects,** every stream stops (so nothing plays from the
  phone speaker) and a "Bluetooth connection lost" notice appears. Press GO when it is back.
- **Android Auto:** declared as a media app. Streams appear as a list; tapping one toggles its mute.
- Streams reconnect automatically every 5 seconds if the connection drops.
- Only one copy of the app can be open at a time.

## The screen

- **Sign button, top right:** a green **GO** roundel starts the streams; while they run it is a red
  **STOP** octagon that stops everything.
- **Menu (three bars):** Configure, Help, About, Back (closes the menu) and Quit
  (stops everything and closes the app).
- **Speaker:** mutes or unmutes that source (green = playing, red = muted, grey = not
  connected). A muted source stays connected, so its tag keeps updating. If a locked tag is on air,
  tapping the speaker lifts the lock.

## Setting up sources (Menu > Configure)

**Icecast2:** enter the server name or IP and the port (default 8000), plus a user name and
password only if the mount needs one, then press **Connect**. The app lists the server's mounts;
pick one and press **Apply**. If the mount needs a login and the user name is blank, `listener` is
used. Errors (name not found, connection refused, timeout, HTTP codes and so on) are shown in words.
If the public status page lists nothing and you gave an admin password, the admin mount list is
tried as well.

**OpenWebRX+:** enter the receiver's address and its port (usually 8073), press **Connect**, choose a
profile from the list, then the mode and the frequency in MHz, then **Apply**. For DMR you can pick
a timeslot (both, 1 or 2). Choosing a different profile changes it on the receiver for everyone
listening to that SDR.

Addresses can be an IP address or a domain name. Plain `http` is assumed; for an encrypted (https)
server use port 443, or type `https://` in front of the address.

Settings also has a tick-box to start the streams automatically when the app opens.

## Install a release

Source code and releases: https://github.com/mw0cqu/Cacophonics

Download `app-release.apk` from the latest release on your phone and open it. Allow your browser to
install unknown apps when asked. Android or Play Protect may warn that the app is from an unknown
developer, because it is not from the Play Store; choose **Install anyway**.

## Build it (no Kotlin knowledge needed)

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Unzip the project into a **new, empty folder**. **File > Open** and choose the folder that
   contains `settings.gradle.kts`. Let it sync (first time it downloads tools; this takes a few
   minutes and needs internet).
3. If Android Studio reports a Gradle JDK problem, set the Gradle JDK to **JVM 21**
   (Settings > Build, Execution, Deployment > Build Tools > Gradle).
4. If it says "SDK location not found", create a file called `local.properties` in the project
   folder containing `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` (Windows example).
5. On your phone: **Settings > About phone**, tap **Build number** 7 times, then
   **Settings > System > Developer options > USB debugging** (or Wireless debugging).
6. Plug the phone in, choose it in the device drop-down at the top of Android Studio and press the
   green **Run** button.

To get an installable file for another device (such as a car stereo): **Build > Build Bundle(s) /
APK(s) > Build APK(s)**, then copy `app/build/outputs/apk/debug/app-debug.apk` to the device and
open it there.

If Android Studio says "Compilation is not supported for the following modules", choose **Remove
unsupported modules and re-sync**; if it comes back, close the project, delete the `.idea` folder
and any `.iml` files in the project folder, and open it again.

## Tech notes

- Application ID: `uk.mw0cqu.cacophonics`.
- Kotlin; minSdk 21, compileSdk/targetSdk 35; Android Gradle Plugin 8.7.3, Gradle 8.10.2 (needs
  JDK 21 or lower), Kotlin 2.0.21.
- Libraries: media3 ExoPlayer 1.4.1 (Icecast), AndroidX Media, Material, AppCompat, OkHttp 4.12.0
  (OpenWebRX+ WebSocket and the Icecast mount lookup).
- OpenWebRX+ audio is decoded in the app (12 kHz mono, IMA ADPCM or PCM) and played through its own
  audio track per source. Written against OpenWebRX+ 1.2.125.
- The app has been written without being able to compile or run it on a device, so treat every
  build as a test build and report problems with the Logcat tag `OWRX` (OpenWebRX+) where relevant.

## Security notes

- Passwords are stored in the app's private storage on the phone, **not encrypted**, and Android's
  backup may include them.
- Plain `http://` and `ws://` are allowed on purpose, since most Icecast and OpenWebRX+ servers use
  them. Anything on the same network could read the streams and the passwords sent to those servers,
  so be careful on shared Wi-Fi.
- The OpenWebRX+ digital voice decoders available depend on your receiver's build.

## Known gaps and ideas

- Not built: configurable number of streams, disconnect/reconnect log, bookmark picker for
  OpenWebRX+, an iPhone version (would be a rewrite and needs a Mac).
- A first-draft Help screen and an About screen (with the acknowledgements above) are in the
  menu.

## Licence

Copyright (C) 2026 Rob (MW0CQU).

This program is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License** as published by the Free Software Foundation, either **version 3** of
the License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but **without any warranty**; without even the implied warranty of merchantability or fitness
for a particular purpose. See the [`LICENSE`](LICENSE) file, or https://www.gnu.org/licenses/gpl-3.0.html.

It uses these open-source libraries, all under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0): AndroidX Media3 / ExoPlayer, AndroidX libraries and
Material Components (Google), OkHttp (Square, Inc.) and Kotlin (JetBrains).

Credits: inspired by Bob Aune's ProScan (https://www.proscan.org) and the Icecast2 project
(https://icecast.org); works with OpenWebRX+ (https://github.com/luarvique/openwebrx). None of their
code is included.
