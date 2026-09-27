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
