package com.raphael.handmouse.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.raphael.handmouse.ActionActivity
import com.raphael.handmouse.R
import com.raphael.handmouse.util.Prefs

/**
 * Quick Settings tiles (Eye Tools fork): start/stop recording and take a photo from the phone's
 * quick panel — nothing opens on the DeX screen. Feedback goes to the capture notification.
 */
class RecordTileService : TileService() {

    companion object {
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, RecordTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        val recording = EyeCaptureService.getInstance()?.isRecording == true
        qsTile?.apply {
            state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(if (recording) R.string.btn_record_stop else R.string.tile_record)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (EyeCaptureService.getInstance() != null) {
            EyeCaptureService.perform(this, EyeAction.RECORD_TOGGLE)
            onStartListening()
        } else {
            // Capture not running: starting its foreground service needs a foreground context,
            // which the invisible ActionActivity provides (nothing is drawn).
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("xreal-eye://action/" + EyeAction.RECORD_START.deepLink))
                .setClass(this, ActionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        }
    }
}

class PhotoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (EyeCaptureService.getInstance() == null) Tile.STATE_UNAVAILABLE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        EyeCaptureService.perform(this, EyeAction.PHOTO)
    }
}

/**
 * A Quick Settings tile for an on/off app setting: active while [isOn], a tap flips it with [set].
 * The services that use the setting follow its saved value on their own (preference listeners),
 * so the tile only writes it. The Remote's ⚙ sheet writes the same settings and calls [refresh]
 * of the tile's class, so a tile on screen shows the change.
 */
abstract class PrefToggleTileService : TileService() {
    protected abstract fun isOn(prefs: Prefs): Boolean

    protected abstract fun set(prefs: Prefs, on: Boolean)

    override fun onStartListening() {
        super.onStartListening()
        val on = isOn(Prefs(this))
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val prefs = Prefs(this)
        set(prefs, !isOn(prefs))
        onStartListening()
    }
}

/** Auto-dimming of the glasses from the phone's light sensor ([Prefs.dimAuto]). */
class DimTileService : PrefToggleTileService() {
    companion object {
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, DimTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun isOn(prefs: Prefs) = prefs.dimAuto

    override fun set(prefs: Prefs, on: Boolean) {
        prefs.dimAuto = on
    }
}

/** Hand tracking gestures ([Prefs.trackingEnabled]); the capture service follows the setting. */
class HandGestureTileService : PrefToggleTileService() {
    companion object {
        fun refresh(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, HandGestureTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun isOn(prefs: Prefs) = prefs.trackingEnabled

    override fun set(prefs: Prefs, on: Boolean) {
        prefs.trackingEnabled = on
    }
}
