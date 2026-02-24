package com.runelitetablet

import android.app.PendingIntent
import android.os.Build

object PendingIntentCompat {
    /** Mutable flags for PendingIntents sent to Termux (requires fill-in extras) */
    val FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }

    /** Immutable flags for notification PendingIntents (no external modification needed) */
    val IMMUTABLE_FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
}
