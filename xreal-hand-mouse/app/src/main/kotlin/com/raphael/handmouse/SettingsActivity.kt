package com.raphael.handmouse

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.KeyEvent
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.raphael.handmouse.service.HandMouseAccessibilityService
import com.raphael.handmouse.util.KEY_BTN_PHOTO
import com.raphael.handmouse.util.KEY_BTN_RECORD
import com.raphael.handmouse.util.KEY_BTN_TRACKING
import com.raphael.handmouse.util.KEY_DIM_START_LUX
import com.raphael.handmouse.util.PREFS_FILE_NAME

/**
 * Eye Tools fork settings (hand mouse tuning, gesture set, recorder, background). Writes to the
 * same SharedPreferences file as [com.raphael.handmouse.util.Prefs]; the services listen for
 * changes and apply them live (no restart needed).
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private val buttonKeys = listOf(KEY_BTN_RECORD, KEY_BTN_PHOTO, KEY_BTN_TRACKING)
        private var sensorManager: SensorManager? = null
        private val ambientLightListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                findPreference<Preference>(KEY_DIM_START_LUX)?.summary =
                    getString(R.string.pref_dim_start_lux_current, event.values[0])
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in buttonKeys) refreshButtonSummaries()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = PREFS_FILE_NAME
            setPreferencesFromResource(R.xml.preferences, rootKey)
            // Button assignment: tap → the next button press anywhere is captured by the
            // accessibility service (it is the only component that sees key events system-wide).
            for (key in buttonKeys) {
                findPreference<Preference>(key)?.setOnPreferenceClickListener { pref ->
                    if (HandMouseAccessibilityService.instance == null) {
                        pref.summary = getString(R.string.pref_btn_need_a11y)
                    } else {
                        HandMouseAccessibilityService.startLearning(key)
                        pref.summary = getString(R.string.pref_btn_press_now)
                        // back to the real value if nothing was pressed in time
                        listView?.postDelayed({ refreshButtonSummaries() }, HandMouseAccessibilityService.LEARN_TIMEOUT_MS + 200)
                    }
                    true
                }
            }
            findPreference<Preference>("add_tiles")?.setOnPreferenceClickListener {
                requestAddTiles()
                true
            }
            findPreference<Preference>("btn_clear")?.setOnPreferenceClickListener {
                preferenceManager.sharedPreferences?.edit()?.apply { buttonKeys.forEach { remove(it) } }?.apply()
                refreshButtonSummaries()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
            refreshButtonSummaries()
            sensorManager = requireContext().getSystemService(SensorManager::class.java)
            val light = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            if (light == null || sensorManager?.registerListener(
                    ambientLightListener, light, SensorManager.SENSOR_DELAY_NORMAL,
                ) != true
            ) {
                findPreference<Preference>(KEY_DIM_START_LUX)?.summary =
                    getString(R.string.pref_dim_start_lux_unavailable)
            }
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
            sensorManager?.unregisterListener(ambientLightListener)
            sensorManager = null
        }

        /** Asks the system to add the Eye record / photo tiles to the quick panel (one system
         * dialog per tile, the second after the first is answered). */
        private fun requestAddTiles() {
            val ctx = requireContext()
            val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java) ?: return
            val tiles = listOf(
                Triple(com.raphael.handmouse.service.RecordTileService::class.java, R.string.tile_record, android.R.drawable.presence_video_online),
                Triple(com.raphael.handmouse.service.PhotoTileService::class.java, R.string.tile_photo, android.R.drawable.ic_menu_camera),
            )
            fun ask(i: Int) {
                val (cls, label, icon) = tiles.getOrNull(i) ?: return
                sbm.requestAddTileService(
                    android.content.ComponentName(ctx, cls),
                    getString(label),
                    android.graphics.drawable.Icon.createWithResource(ctx, icon),
                    ctx.mainExecutor,
                ) { ask(i + 1) }
            }
            ask(0)
        }

        private fun refreshButtonSummaries() {
            val sp = preferenceManager.sharedPreferences ?: return
            for (key in buttonKeys) {
                val code = sp.getInt(key, 0)
                findPreference<Preference>(key)?.summary =
                    if (code == 0) getString(R.string.pref_btn_none)
                    else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
            }
        }
    }
}
