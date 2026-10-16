# Cacophonics 1.0.12

Many radios, one phone. Listen to up to six Icecast2 streams and OpenWebRX+ receivers at once, with
each source's tag on screen, on Bluetooth and on the car stereo.

Highlights
- Six sources, any mix of Icecast2 and OpenWebRX+, each with volume and mute.
- Tag display, and Bluetooth / car "N: tag" metadata. Works with Android Auto, but does not need it.
- Tag lockout for Icecast streams, squelch for OpenWebRX+ analog modes, digital voice modes.
- Receiver check at start, and all streams stop if the Bluetooth connection is lost.
- Android 5.0 and newer. Free software, GPL v3.

Known gaps
- Stream passwords are stored unencrypted on the phone.
- Plain http/ws connections are allowed (most Icecast and OpenWebRX+ servers use them).
- Some digital voice modes and Android Auto behaviour have had limited testing.

Install: download `app-release.apk`, allow "Install unknown apps" when asked, and open it. Android
or Play Protect may warn that the app is from an unknown developer; choose "Install anyway".

What is new in 1.0.12: the lock screen now shows one notification: the stream you are on as the heading, and
the other streams on one compact line (tags shortened, for example `2:Cardi…  3:Scann…  4:MW0ABC`). Open it
out (the arrow) and you get all six streams on the lock screen's notification page, with the stream you are on
in bold. A tap asks for your PIN or fingerprint and opens the app. It has no buttons, so nothing can be muted
or stopped by accident in a pocket. It replaces the media card (Previous / Next / Pause) on the lock screen;
those buttons still work from a car stereo and inside the app. Bluetooth and car displays no longer get the app
name: line 1 is the stream you are on, line 2 is the others, line 3 is that stream's name.
If the lock screen shows nothing, your phone is hiding quiet notifications there: in Settings > Notifications >
Notifications on lock screen, choose the option that includes silent notifications.

What was new in 1.0.7: the menu entry "Check for updates" is now simply "Updates".

What was new in 1.0.6: a "Check for updates" entry in the menu. It asks GitHub for the newest release and
says whether a newer version exists, with a Download button. The delete-all warning is now a
hazard-striped CAUTION box whose red button fills while you hold it.

What was new in 1.0.5: press and hold the GO / STOP sign to delete all stream data (after a CAUTION
warning you must press and hold to confirm). The menu no longer has a "Back" entry.

What was new in 1.0.4: typing port 443 now makes the app use https automatically, so the https://
prefix is no longer needed on port 443.

What was new in 1.0.3: OpenWebRX+ analog sources now start with the squelch fully closed, so nothing
is heard until you slide it open. OpenWebRX+ sources are mono; Icecast streams keep what the server sends.

What was new in 1.0.2: OpenWebRX+ sources now have a separate port box like Icecast ones, the example
text in the address boxes is shortened, and wideband FM (WFM) on OpenWebRX+ now plays (it uses the
receiver's HD audio stream, which the app was ignoring).

What was new in 1.0.1: the About screen and README link to the source code.
