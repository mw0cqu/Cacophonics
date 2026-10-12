// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/**
 * Sits in a stream's audio path and reports how loud each chunk of decoded audio is.
 * The audio itself passes through untouched.
 *
 * [onLevel] is called on the audio thread with the chunk's peak level, 0.0 (silence) to 1.0 (full scale).
 */
@OptIn(UnstableApi::class)
class LevelProcessor(private val onLevel: (Float) -> Unit) : BaseAudioProcessor() {

    private var encoding = C.ENCODING_INVALID

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        return inputAudioFormat // same format out as in: pure pass-through
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        if (encoding == C.ENCODING_PCM_16BIT) {
            var peak = 0
            var i = inputBuffer.position()
            val end = inputBuffer.limit() - 1
            while (i < end) {
                // 16-bit little-endian sample, read byte by byte so byte order never matters
                val sample = (inputBuffer.get(i + 1).toInt() shl 8) or (inputBuffer.get(i).toInt() and 0xFF)
                peak = max(peak, abs(sample))
                i += 4 // look at every other sample; plenty to detect sound
            }
            onLevel(peak / 32768f)
        }

        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onFlush() {
        onLevel(0f)
    }
}
