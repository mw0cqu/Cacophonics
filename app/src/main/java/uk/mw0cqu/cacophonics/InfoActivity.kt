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

    private fun aboutText(): String {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return "${getString(R.string.app_name)}\nMany radios, one phone.\nVersion $version\n\n" +
            "Listen to several Icecast2 streams and OpenWebRX+ receivers at the same time, " +
            "with each stream's tag shown on screen, on Bluetooth and on the car stereo.\n\n" +
            "This project was inspired by the work of others, and gratefully acknowledges:\n\n" +
            "• Bob Aune and ProScan – https://www.proscan.org\n\n" +
            "• The Icecast2 project – https://icecast.org\n\n" +
            "• The OpenWebRX+ project – https://github.com/luarvique/openwebrx"
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
        "These show the frequency, mode and bookmark name. Analog modes have a squelch slider: left is open, further right needs a stronger signal. Digital voice modes do not use squelch.\n\n" +
        "Source Settings\n" +
        "Open the menu (three bars, top right) and choose Source Settings. Icecast: enter the server and port, press Connect, choose a mountpoint from the list, then Apply. OpenWebRX+: enter the receiver address, press Connect, choose a profile, mode and frequency, then Apply.\n\n" +
        "When Bluetooth disconnects\n" +
        "All streams stop so nothing plays from the phone speaker. Press GO when the Bluetooth connection is back.\n\n" +
        "Menu\n" +
        "Source Settings, Help, About, Back (return to the streams) and Quit (stops everything and closes the app)."
