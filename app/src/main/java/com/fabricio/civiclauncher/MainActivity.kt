package com.fabricio.civiclauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

object Sources {
    const val USB_PKG = "com.hcn.AutoMediaPlayer"            // app "Music" (USB)
    const val YTM_PKG = "com.google.android.apps.youtube.music"
    const val BT_MUSIC_PKG = ""                              // TODO: paquete de BT Music
    private val btPkgs = setOf("com.android.bluetooth", BT_MUSIC_PKG).filter { it.isNotBlank() }

    fun of(pkg: String) = when {
        pkg == USB_PKG -> "usb"
        pkg == YTM_PKG -> "ytm"
        pkg in btPkgs -> "bt"
        else -> "other"
    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var audio: AudioManager
    private lateinit var lm: LocationManager
    private val ui = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var speedKmh = 0f
    private var lastFix = 0L

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            lastFix = SystemClock.elapsedRealtime()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startGps()
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startGps()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener) } catch (e: Exception) {}
    }

    private val ticker = object : Runnable {
        override fun run() { push(); ui.postDelayed(this, 1000) }
    }
    override fun onResume() { super.onResume(); ui.post(ticker) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(ticker) }
    override fun onDestroy() {
        try { lm.removeUpdates(locListener) } catch (e: Exception) {}
        super.onDestroy()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* es el launcher: no se cierra */ }

    private fun hasAccess() =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(packageName) == true

    private fun pickController(): MediaController? {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = try {
            msm.getActiveSessions(ComponentName(this, MediaListenerService::class.java))
        } catch (e: SecurityException) { return null }
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }

    private fun push() {
        val c = pickController()
        controller = c
        val o = JSONObject()
        o.put("access", hasAccess())
        o.put("night", (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        o.put("vol", audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
        val gps = SystemClock.elapsedRealtime() - lastFix < 5000
        o.put("gps", gps)
        o.put("speed", if (gps) Math.round(speedKmh) else 0)
        if (c != null) {
            val m = c.metadata
            val ps = c.playbackState
            o.put("source", Sources.of(c.packageName))
            o.put("title", m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            o.put("artist", m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            o.put("dur", (m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L) / 1000)
            var pos = ps?.position ?: 0L
            val playing = ps?.state == PlaybackState.STATE_PLAYING
            if (ps != null && playing) pos += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
            o.put("pos", pos / 1000)
            o.put("playing", playing)
        }
        web.evaluateJavascript("window.onState && window.onState($o)", null)
    }

    private fun key(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun installed(): List<Pair<String, String>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(i, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .sortedBy { it.first.lowercase() }
    }

    private fun startPkg(p: String) {
        val i = packageManager.getLaunchIntentForPackage(p)
        if (i != null) startActivity(i) else toast("App no instalada: $p")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun open(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("No se pudo abrir") }
    }

    inner class Bridge {
        @JavascriptInterface fun cmd(c: String) {
            ui.post {
                val t = controller?.transportControls
                when (c) {
                    "playpause" ->
                        if (t == null) key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        else if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                    "next" -> if (t != null) t.skipToNext() else key(KeyEvent.KEYCODE_MEDIA_NEXT)
                    "prev" -> if (t != null) t.skipToPrevious() else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
            }
        }
        @JavascriptInterface fun launchPkg(p: String) { ui.post { startPkg(p) } }
        @JavascriptInterface fun launchLabel(l: String) {
            ui.post {
                val a = installed().firstOrNull { it.first.contains(l, ignoreCase = true) }
                if (a != null) startPkg(a.second) else toast("No encontré una app: $l")
            }
        }
        @JavascriptInterface fun apps(): String {
            val arr = JSONArray()
            installed().forEach { arr.put(JSONObject().put("label", it.first).put("pkg", it.second)) }
            return arr.toString()
        }
        @JavascriptInterface fun openAccess() { ui.post { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } }
        @JavascriptInterface fun openSettings() { ui.post { open(Intent(Settings.ACTION_SETTINGS)) } }
        @JavascriptInterface fun openMaps() { ui.post { open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))) } }
        @JavascriptInterface fun dial() { ui.post { open(Intent(Intent.ACTION_DIAL)) } }
    }
}
