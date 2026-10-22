package com.example.fluidwall

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile

class MainActivity : ComponentActivity() {

    private lateinit var tvPics: TextView
    private lateinit var tvLive: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etDuration: EditText
    private lateinit var etLiveEvery: EditText
    private lateinit var spUnit: Spinner

    private val units = listOf("s", "m", "h")
    private val unitSeconds = listOf(1L, 60L, 3600L)

    private val prefs by lazy { getSharedPreferences(VideoWallpaperService.PREFS, Context.MODE_PRIVATE) }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == VideoWallpaperService.KEY_CURRENT) {
                runOnUiThread { showCurrent(p) }
            }
        }

    private val pickPics =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) saveFolder(VideoWallpaperService.KEY_PIC, uri, tvPics)
        }

    private val pickLive =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) saveFolder(VideoWallpaperService.KEY_LIVE, uri, tvLive)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvPics = findViewById(R.id.tvPics)
        tvLive = findViewById(R.id.tvLive)
        tvCurrent = findViewById(R.id.tvCurrent)
        tvStatus = findViewById(R.id.tvStatus)
        etDuration = findViewById(R.id.etDuration)
        etLiveEvery = findViewById(R.id.etLiveEvery)
        spUnit = findViewById(R.id.spUnit)

        spUnit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, units
        )

        loadSettings()

        findViewById<Button>(R.id.btnPickPics).setOnClickListener { pickPics.launch(null) }
        findViewById<Button>(R.id.btnPickLive).setOnClickListener { pickLive.launch(null) }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnSetWallpaper).setOnClickListener { openWallpaperPicker() }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        showCurrent(prefs)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onStop()
    }

    private fun loadSettings() {
        tvPics.text = folderName(prefs.getString(VideoWallpaperService.KEY_PIC, null))
        tvLive.text = folderName(prefs.getString(VideoWallpaperService.KEY_LIVE, null))

        // Show the saved interval in the largest unit that divides it evenly.
        val secs = prefs.getLong(VideoWallpaperService.KEY_INTERVAL, 1800L)
        val (amount, unitIdx) = when {
            secs % 3600 == 0L -> Pair(secs / 3600, 2)
            secs % 60 == 0L -> Pair(secs / 60, 1)
            else -> Pair(secs, 0)
        }
        etDuration.setText(amount.toString())
        spUnit.setSelection(unitIdx)

        etLiveEvery.setText(prefs.getInt(VideoWallpaperService.KEY_LIVE_EVERY, 3).toString())
        showCurrent(prefs)
    }

    private fun saveFolder(key: String, uri: Uri, view: TextView) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(key, uri.toString()).apply()
        view.text = folderName(uri.toString())
    }

    private fun saveSettings() {
        val amount = etDuration.text.toString().toLongOrNull()
        if (amount == null || amount <= 0) {
            toast("Enter a valid duration")
            return
        }
        val seconds = amount * unitSeconds[spUnit.selectedItemPosition]
        if (seconds < MIN_SECONDS) {
            toast("Minimum is ${MIN_SECONDS}s")
            return
        }
        val liveEvery = etLiveEvery.text.toString().toIntOrNull()
        if (liveEvery == null) {
            toast("Live every must be a whole number")
            return
        }

        prefs.edit()
            .putLong(VideoWallpaperService.KEY_INTERVAL, seconds)
            .putInt(VideoWallpaperService.KEY_LIVE_EVERY, liveEvery)
            .apply()
        tvStatus.text = "Saved: every $amount${units[spUnit.selectedItemPosition]}, live every $liveEvery"
        toast("Saved. Running wallpaper picks this up right away.")
    }

    private fun openWallpaperPicker() {
        val component = ComponentName(this, VideoWallpaperService::class.java)
        val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    private fun showCurrent(p: SharedPreferences) {
        tvCurrent.text = "Current: " + (p.getString(VideoWallpaperService.KEY_CURRENT, null) ?: "none yet")
    }

    private fun folderName(uriStr: String?): String {
        if (uriStr == null) return "No folder selected"
        return DocumentFile.fromTreeUri(this, Uri.parse(uriStr))?.name ?: uriStr
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val MIN_SECONDS = 5L
    }
}
