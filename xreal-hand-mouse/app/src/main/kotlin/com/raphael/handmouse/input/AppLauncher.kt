package com.raphael.handmouse.input

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * Lança um app pelo nome FALADO (2026-07-23, spec `2026-07-23-voice-control-design.md`):
 * enumera as activities de launcher via PackageManager, casa o nome com [AppNameMatcher]
 * e dispara o launch intent NO DISPLAY DO DEX ([ActivityOptions.setLaunchDisplayId] — o
 * comando de voz é pra tela dos óculos, não pra do celular; VALIDAR EM HARDWARE: o DeX
 * costuma rotear sozinho, o launchDisplayId é o cinto-e-suspensório).
 *
 * A lista de apps é consultada A CADA chamada (sem cache): a enumeração é rápida no volume
 * típico (~200 apps) e instalações/desinstalações ficam sempre frescas.
 *
 * Não testável em JVM puro (PackageManager/Context reais) — o casamento, que é a lógica de
 * verdade, vive no [AppNameMatcher] (testado). Toda ação é logada (brief).
 */
class AppLauncher(private val context: Context) {

    companion object {
        private const val TAG = "AppLauncher"
    }

    /** Retorna `true` se um app casou E o intent foi disparado. */
    fun launch(query: String, displayId: Int): Boolean {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0L),
        ).map { AppNameMatcher.InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }

        val match = AppNameMatcher.match(query, apps)
        if (match == null) {
            Log.w(TAG, "Nenhum app instalado casa com \"$query\" (${apps.size} candidatos)")
            return false
        }

        val intent = pm.getLaunchIntentForPackage(match.packageName)
        if (intent == null) {
            Log.w(TAG, "App ${match.packageName} sem launch intent")
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic().apply {
            if (displayId >= 0) launchDisplayId = displayId
        }
        return try {
            context.startActivity(intent, options.toBundle())
            Log.d(TAG, "\"$query\" → ${match.label} (${match.packageName}) no display $displayId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao lançar ${match.packageName} no display $displayId", e)
            false
        }
    }
}
