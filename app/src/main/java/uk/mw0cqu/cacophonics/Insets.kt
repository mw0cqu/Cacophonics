// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rob (MW0CQU)

package uk.mw0cqu.cacophonics

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ draws apps edge to edge, which puts content under the status bar, camera cut-out
 * and navigation bar. This keeps the screen's content inside the safe area.
 * Call it right after setContentView().
 */
fun AppCompatActivity.keepInsideSystemBars() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        // ime() = the on-screen keyboard, so the content shrinks to sit above it.
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
