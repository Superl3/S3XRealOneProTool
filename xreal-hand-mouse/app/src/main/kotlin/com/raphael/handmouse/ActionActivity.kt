package com.raphael.handmouse

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.raphael.handmouse.service.EyeAction
import com.raphael.handmouse.service.EyeCaptureService

/**
 * Invisible entry point for launchers and voice assistants (Eye Tools fork): app shortcuts and
 * `xreal-eye://action/<name>` deep links (Bixby Routines / Modes and Routines, Tasker, assistant
 * shortcuts) land here and are handed to [EyeCaptureService.perform].
 *
 * Theme.NoDisplay + finish() in onCreate: nothing is ever drawn, so the DeX screen is untouched.
 * Voice stays hierarchical: the phone's assistant does the speech recognition; the app only
 * executes the action.
 */
class ActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = EyeAction.fromDeepLink(intent?.data?.lastPathSegment)
        if (action != null) EyeCaptureService.perform(this, action) else Log.w("ActionActivity", "No action in $intent")
        finish()
    }
}
