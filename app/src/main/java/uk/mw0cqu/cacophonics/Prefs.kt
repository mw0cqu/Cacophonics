// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.content.Context

/** One configured stream slot. A slot with a blank URL is "not configured". */
data class StreamConfig(
    val name: String = "",
    val url: String = "",
    val user: String = "",
    val pass: String = "",
    /** [Prefs.TYPE_ICECAST] or [Prefs.TYPE_OWRX]. The fields below are only used for OpenWebRX+. */
    val type: String = Prefs.TYPE_ICECAST,
    /** Frequency in MHz, as typed. */
    val freq: String = "",
    val mode: String = "nfm",
    /** Optional "sdrId|profileId" to select on the receiver. */
    val profile: String = "",
    /** The profile's name as the receiver shows it, e.g. "RTL-SDR1:430-440MHz". For display only. */
    val profileName: String = "",
    /** DMR timeslot filter: "3" both, "1" slot 1, "2" slot 2. */
    val slot: String = "3"
) {
    val isOwrx: Boolean get() = type == Prefs.TYPE_OWRX
    val enabled: Boolean get() = url.isNotBlank()
    fun label(index: Int): String = name.ifBlank { "Stream ${index + 1}" }
}

/** Live, in-memory state of one stream, owned by [StreamService]. */
class StreamState {
    var active = false

    /** True while the stream is actually carrying audible sound (not just connected). */
    @Volatile
    var audio = false
    var muted = false

    /** True while the tag showing now is on this stream's lockout list (the sound is held off). */
    var locked = false

    /** How many tags are locked out on this stream. */
    var lockCount = 0

    /** Not audible, either muted by hand or because the current tag is locked out. */
    val silent: Boolean get() = muted || locked

    /** This stream's volume slider, 0..100 (100 = full). Independent of muting. */
    var volume = 100

    /** This stream's squelch slider, 0..100 (0 = off). Only used by OpenWebRX+ sources. */
    var squelch = Prefs.DEFAULT_SQUELCH
    var tag = ""
    var status = "Stopped"
}

object Prefs {
    const val SLOTS = 6
    const val TYPE_ICECAST = "icecast"
    const val TYPE_OWRX = "openwebrx"

    private fun sp(c: Context) = c.getSharedPreferences("streams", Context.MODE_PRIVATE)

    fun load(c: Context): List<StreamConfig> {
        val p = sp(c)
        return List(SLOTS) { i ->
            StreamConfig(
                name = p.getString("name$i", "") ?: "",
                url = (p.getString("url$i", "") ?: "").trim(),
                user = p.getString("user$i", "") ?: "",
                pass = p.getString("pass$i", "") ?: "",
                type = p.getString("type$i", TYPE_ICECAST) ?: TYPE_ICECAST,
                freq = p.getString("freq$i", "") ?: "",
                mode = p.getString("mode$i", "nfm") ?: "nfm",
                profile = p.getString("profile$i", "") ?: "",
                profileName = p.getString("pname$i", "") ?: "",
                slot = p.getString("slot$i", "3") ?: "3"
            )
        }
    }

    fun save(c: Context, list: List<StreamConfig>) {
        val e = sp(c).edit()
        list.forEachIndexed { i, s ->
            e.putString("name$i", s.name)
            e.putString("url$i", s.url.trim())
            e.putString("user$i", s.user)
            e.putString("pass$i", s.pass)
            e.putString("type$i", s.type)
            e.putString("freq$i", s.freq)
            e.putString("mode$i", s.mode)
            e.putString("profile$i", s.profile)
            e.putString("pname$i", s.profileName)
            e.putString("slot$i", s.slot)
        }
        e.apply()
    }

    fun autostart(c: Context): Boolean = sp(c).getBoolean("autostart", true)

    fun setAutostart(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("autostart", value).apply()
    }

    fun volume(c: Context, i: Int): Int = sp(c).getInt("vol$i", 100).coerceIn(0, 100)

    /** Until the slider has been moved the squelch starts closed at 40 (about -90 dB), not off. */
    const val DEFAULT_SQUELCH = 40

    fun squelch(c: Context, i: Int): Int = sp(c).getInt("sqv$i", DEFAULT_SQUELCH).coerceIn(0, 100)

    fun setSquelch(c: Context, i: Int, value: Int) {
        sp(c).edit().putInt("sqv$i", value.coerceIn(0, 100)).apply()
    }

    fun setVolume(c: Context, i: Int, value: Int) {
        sp(c).edit().putInt("vol$i", value.coerceIn(0, 100)).apply()
    }
}
