package com.runelitetablet

import android.app.PendingIntent
import android.os.Build

object PendingIntentCompat {
    val FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    } else {
        PendingIntent.FLAG_UPDATE_CURRENT
    }
}
