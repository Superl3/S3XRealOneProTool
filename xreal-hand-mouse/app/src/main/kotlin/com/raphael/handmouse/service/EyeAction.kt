package com.raphael.handmouse.service

import android.content.Intent

/**
 * Every recorder / tracking command (Eye Tools fork), whatever triggered it: notification button,
 * quick-settings tile, hardware button, assistant shortcut / deep link, or the app screen. All of
 * them go through [EyeCaptureService.perform], so they behave the same whether or not capture is
 * running.
 */
enum class EyeAction(val deepLink: String) {
    RECORD_START("record-start"),
    RECORD_STOP("record-stop"),
    RECORD_TOGGLE("record-toggle"),
    PHOTO("photo"),
    TRACKING_ON("tracking-on"),
    TRACKING_OFF("tracking-off"),
    TRACKING_TOGGLE("tracking-toggle"),
    CAPTURE_STOP("capture-stop");

    /** Intent action used for PendingIntents / service starts. */
    val intentAction: String get() = PREFIX + name

    companion object {
        private const val PREFIX = "com.raphael.handmouse.action."

        fun fromIntent(intent: Intent?): EyeAction? {
            val a = intent?.action ?: return null
            return if (a.startsWith(PREFIX)) entries.firstOrNull { it.name == a.removePrefix(PREFIX) } else null
        }

        fun fromDeepLink(segment: String?): EyeAction? = entries.firstOrNull { it.deepLink == segment }
    }
}
