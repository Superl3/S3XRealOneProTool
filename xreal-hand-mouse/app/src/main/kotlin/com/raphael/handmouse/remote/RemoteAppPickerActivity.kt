package com.raphael.handmouse.remote

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import androidx.core.widget.addTextChangedListener

class RemoteAppPickerActivity : Activity() {
    private data class Entry(val label: String, val packageName: String?) {
        override fun toString(): String = if (packageName == null) label else "$label  ·  $packageName"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra(RemoteDockPrefs.EXTRA_SLOT, -1)
        if (slot !in 0 until RemoteDockPrefs.SLOT_COUNT) {
            finish()
            return
        }

        title = "DeX Dock ${slot + 1}"
        val entries = loadApps()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries)

        val search = EditText(this).apply {
            hint = "앱 이름 또는 패키지 검색"
            setSingleLine(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addTextChangedListener { adapter.filter.filter(it?.toString().orEmpty()) }
            setOnEditorActionListener { _, _, _ ->
                if (adapter.count == 1) {
                    adapter.getItem(0)?.let { selectEntry(slot, it) }
                    true
                } else {
                    false
                }
            }
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                adapter.getItem(position)?.let { selectEntry(slot, it) }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(list, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
        }
        setContentView(root)
    }

    private fun selectEntry(slot: Int, entry: Entry) {
        RemoteDockPrefs(this).setPackage(slot, entry.packageName)
        setResult(RESULT_OK, Intent().putExtra(RemoteDockPrefs.EXTRA_SLOT, slot))
        finish()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun loadApps(): List<Entry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(
            launcher,
            PackageManager.ResolveInfoFlags.of(0L),
        ).map {
            Entry(it.loadLabel(packageManager).toString(), it.activityInfo.packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        return listOf(Entry("비우기", null)) + apps
    }
}

