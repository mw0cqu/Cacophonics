# Cacophonics 1.0

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

Install: download `app-release.apk`, allow "Install unknown apps" when asked, and open it.
