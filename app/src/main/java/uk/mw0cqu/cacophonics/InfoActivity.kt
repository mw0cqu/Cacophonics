// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** The Help and About screens (plain scrolling text, with tappable web links). */
class InfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_HELP = "help"
        const val PAGE_ABOUT = "about"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_info)
        keepInsideSystemBars()

        val about = intent.getStringExtra(EXTRA_PAGE) == PAGE_ABOUT
        findViewById<TextView>(R.id.title).text = if (about) "About" else "Help"
        findViewById<TextView>(R.id.body).text = if (about) aboutText() else HELP_TEXT
        findViewById<android.view.View>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun aboutText(): CharSequence {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val out = android.text.SpannableStringBuilder()
        out.append(getString(R.string.app_name)).append("\n\n")
        val start = out.length
        out.append("Many radios one phone")
        out.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
            start, out.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        out.append("\n\nDeveloped by Rob (MW0CQU)\n\nVersion $version\n\n")
        out.append(
            "Listen to several Icecast2 streams and OpenWebRX+ receivers at the same time, " +
                "with each stream's tag shown on screen, on Bluetooth and on the car stereo.\n\n" +
                "This project was inspired by the work of others, and gratefully acknowledges:\n\n" +
                "• Bob Aune and ProScan – https://www.proscan.org\n\n" +
                "• The Icecast2 project – https://icecast.org\n\n" +
                "• The OpenWebRX+ project – https://github.com/luarvique/openwebrx\n\n" +
                "Source code: https://github.com/mw0cqu/Cacophonics\n\n" +
                "Copyright © 2026 Rob (MW0CQU)\n\n" +
                "This is free software, licensed under the GNU General Public License, version 3 or " +
                "(at your option) any later version. It comes with ABSOLUTELY NO WARRANTY. You may " +
                "share and change it under the terms of that licence: https://www.gnu.org/licenses/gpl-3.0.html\n\n" +
                "Open-source components used (all Apache License 2.0):\n" +
                "• AndroidX Media3 / ExoPlayer, AndroidX libraries and Material Components – Google\n" +
                "• OkHttp – Square, Inc.\n" +
                "• Kotlin – JetBrains\n" +
                "Licence: https://www.apache.org/licenses/LICENSE-2.0"
        )
        return out
    }
}

/** First draft of the help text – to be worked on. */
private const val HELP_TEXT =
    "Start / Stop\n" +
        "The round green GO sign starts all your streams. While they run it becomes a red STOP sign; tap it to stop everything.\n\n" +
        "Each panel\n" +
        "• The big number is the stream number. The same number is put in front of the tag on Bluetooth and car displays, for example \"2: Channel 14\".\n" +
        "• The slider sets that stream's volume.\n" +
        "• The speaker mutes or unmutes that stream. Green = playing, red = muted, grey = not connected.\n" +
        "• A panel turns light grey while sound is actually being received.\n\n" +
        "Tag lockout (Icecast streams)\n" +
        "Tap the tag icon to silence the tag showing now. It flashes green while some tags are locked, and red while a locked tag is on air. Tap it again to unlock. Press and hold it to clear the list. Locked tags are forgotten when the streams stop.\n\n" +
        "OpenWebRX+ panels\n" +
        "These show the frequency, mode and bookmark name. Analog modes have a squelch slider: left is open, further right needs a stronger signal. It starts fully to the right (closed), so you hear nothing until you slide it left to the level you want. Digital voice modes do not use squelch.\n\n" +
        "Configure\n" +
        "Open the menu (three bars, top right) and choose Configure. Icecast: enter the server and port, press Connect, choose a mountpoint from the list, then Apply. OpenWebRX+: enter the receiver address, press Connect, choose a profile, mode and frequency, then Apply.\n\n" +
        "When Bluetooth disconnects\n" +
        "All streams stop so nothing plays from the phone speaker. Press GO when the Bluetooth connection is back.\n\n" +
        "Menu\n" +
        "Configure, Help, About, Check for updates and Quit (stops everything and closes the app).\n\n" +
        "Check for updates\n" +
        "Asks GitHub whether a newer version has been published. If there is one, Download opens it in your browser so you can install it over this version; your streams and settings are kept. It contacts GitHub only when you choose it.\n\n" +
        "Deleting everything\n" +
        "Press and hold the GO / STOP sign. A CAUTION box appears. Press and hold its red button until it has filled to delete all six streams and their volume and squelch settings. Let go early, or tap Go back, and nothing is deleted."
