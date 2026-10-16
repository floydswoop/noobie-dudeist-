package com.example.skyear

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelUuid
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    // State shared between the recording threads.
    private class RecState(val id: String, val startMs: Long) {
        @Volatile var totalBytes = 0L
        @Volatile var source = "UNPROCESSED"
        @Volatile var error: String? = null
        @Volatile var lat: Double? = null
        @Volatile var lon: Double? = null
        @Volatile var acc: Float? = null
        val snaps = JSONArray()
    }

    private lateinit var web: WebView
    private var pendingMic: PermissionRequest? = null
    private var pendingRadius = 15
    private var recRadius = 15

    @Volatile private var recording = false

    // Phone orientation (compass and tilt) while recording
    private var sensorListener: SensorEventListener? = null
    @Volatile private var declination: Float? = null
    @Volatile private var compassWanted = false   // the Field tab radar wants the compass
    @Volatile private var sensorAcc = 0
    private var lastOrientSent = 0L

    // Remote ID scanning state
    @Volatile private var ridOn = false
    @Volatile private var ridLat: Double? = null
    @Volatile private var ridLon: Double? = null
    private var bleCallback: ScanCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private val ridLock = Any()
    private var ridObs = JSONArray()

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingMic
            pendingMic = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
            }
        }

    // Native file picker for "Analyze a recording": copies the chosen file where the page can read it.
    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                Thread {
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            File(cacheDir, "picked.bin").outputStream().use { out -> input.copyTo(out) }
                        } ?: throw Exception("Cannot open the file")
                        sendToPage("onPicked", "ok")
                    } catch (e: Exception) {
                        sendToPage("onPicked", "error")
                    }
                }.start()
            }
        }

    private val locLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.any { it }) {
                fetchOverhead(pendingRadius)
            } else {
                sendToPage("onOverheadError", "Location permission was denied.")
            }
        }

    // Asked when recording starts. Audio is required; location is optional.
    private val recPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            if (hasAudio()) {
                beginRecording()
            } else {
                sendToPage("onRecState", "error:Microphone permission was denied.")
            }
        }

    private val ridPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            startRidScan()
        }

    // Functions the web page can call as Android.something(...)
    inner class Bridge {
        @JavascriptInterface
        fun requestOverhead(radiusNm: Int) {
            runOnUiThread { startOverhead(radiusNm) }
        }

        @JavascriptInterface
        fun startCompass() {
            runOnUiThread {
                compassWanted = true
                if (!startOrientation()) {
                    sendToPage("onOrientError", "No compass sensor found, so the radar cannot show where you point.")
                }
                primeDeclination()
            }
        }

        @JavascriptInterface
        fun stopCompass() {
            runOnUiThread {
                compassWanted = false
                if (!recording) stopOrientation()
            }
        }

        @JavascriptInterface
        fun startRecording(radiusNm: Int) {
            runOnUiThread { startRecordingUi(radiusNm) }
        }

        @JavascriptInterface
        fun startRid() {
            runOnUiThread { startRidUi() }
        }

        @JavascriptInterface
        fun stopRid() {
            runOnUiThread { stopRidScan("stopped") }
        }

        @JavascriptInterface
        fun getPref(key: String): String {
            if (!Regex("[A-Za-z0-9_]+").matches(key)) return ""
            return getSharedPreferences("settings", Context.MODE_PRIVATE).getString(key, "") ?: ""
        }

        @JavascriptInterface
        fun setPref(key: String, value: String) {
            if (!Regex("[A-Za-z0-9_]+").matches(key) || value.length > 200) return
            getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(key, value).apply()
        }

        @JavascriptInterface
        fun pickAudio() {
            runOnUiThread { pickLauncher.launch("audio/*") }
        }

        @JavascriptInterface
        fun stopRecording() {
            recording = false
        }

        @JavascriptInterface
        fun listClips(): String {
            val out = JSONArray()
            val files = clipsDir().listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()
            for (f in files.sortedByDescending { it.name }) {
                try {
                    val m = JSONObject(f.readText())
                    m.remove("snaps")
                    out.put(m)
                } catch (e: Exception) {
                    // skip unreadable entries
                }
            }
            return out.toString()
        }

        @JavascriptInterface
        fun getClip(id: String): String {
            if (!validId(id)) return "{}"
            val f = File(clipsDir(), "$id.json")
            return if (f.exists()) f.readText() else "{}"
        }

        @JavascriptInterface
        fun saveClip(id: String, json: String) {
            if (!validId(id)) return
            File(clipsDir(), "$id.json").writeText(json)
        }

        @JavascriptInterface
        fun deleteClip(id: String) {
            if (!validId(id)) return
            File(clipsDir(), "$id.wav").delete()
            File(clipsDir(), "$id.json").delete()
        }

        @JavascriptInterface
        fun exportClip(id: String) {
            if (validId(id)) exportToDownloads(id)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false

        web.webViewClient = object : WebViewClient() {
            // Only the bundled page may run here, so block all other navigation.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

            // Lets the page read saved clips at https://skyear.local/clips/<id>.wav
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val u = request?.url ?: return null
                if (u.host != "skyear.local") return null
                if (u.path == "/picked") {
                    val pf = File(cacheDir, "picked.bin")
                    if (!pf.exists()) return notFound()
                    return WebResourceResponse(
                        "application/octet-stream", null, 200, "OK",
                        mapOf("Access-Control-Allow-Origin" to "*"),
                        FileInputStream(pf)
                    )
                }
                if (u.path?.startsWith("/clips/") != true) return null
                val name = u.lastPathSegment ?: return notFound()
                if (!Regex("clip_[0-9_]+\\.wav").matches(name)) return notFound()
                val f = File(clipsDir(), name)
                if (!f.exists()) return notFound()
                return WebResourceResponse(
                    "audio/wav", null, 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "*"),
                    FileInputStream(f)
                )
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")

        web.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest?) {
                val req = request ?: return
                runOnUiThread {
                    if (!req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        req.deny()
                        return@runOnUiThread
                    }
                    if (hasAudio()) {
                        req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingMic = req
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }

        val html = assets.open("index.html").bufferedReader().use { it.readText() }
        web.loadDataWithBaseURL("https://skyear.local/", html, "text/html", "utf-8", null)

        val box = FrameLayout(this)
        box.addView(web, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(box) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(box)
    }

    // ---------- helpers ----------

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    private fun clipsDir(): File {
        val d = File(filesDir, "clips")
        d.mkdirs()
        return d
    }

    private fun validId(id: String): Boolean = Regex("clip_[0-9_]+").matches(id)

    private fun sendToPage(fn: String, payload: String) {
        runOnUiThread {
            web.evaluateJavascript("$fn(${JSONObject.quote(payload)})", null)
        }
    }

    private fun hasAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------- overhead (ADS-B) ----------

    private fun startOverhead(radiusNm: Int) {
        pendingRadius = radiusNm.coerceIn(5, 100)
        if (hasLocation()) {
            fetchOverhead(pendingRadius)
        } else {
            locLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // Gets a fresh location fix (up to 10 s), falling back to the last known one.
    @SuppressLint("MissingPermission")
    private fun getFix(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val usable = lm.getProviders(true).filter { fine || it == LocationManager.NETWORK_PROVIDER }
        if (Build.VERSION.SDK_INT >= 30) {
            val p = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { it in usable }
            if (p != null) {
                var got: Location? = null
                val latch = CountDownLatch(1)
                lm.getCurrentLocation(p, null, ContextCompat.getMainExecutor(this)) { l ->
                    got = l
                    latch.countDown()
                }
                latch.await(10, TimeUnit.SECONDS)
                val fix = got
                if (fix != null) return fix
            }
        }
        var best: Location? = null
        for (p in usable) {
            val l = lm.getLastKnownLocation(p) ?: continue
            val b = best
            if (b == null || l.time > b.time) best = l
        }
        return best
    }

    // Asks the ADS-B service for aircraft around a point. Blocking: call from a background thread.
    private fun queryAircraft(lat: Double, lon: Double, radiusNm: Int): JSONArray {
        val conn = URL("https://api.adsb.lol/v2/lat/$lat/lon/$lon/dist/$radiusNm")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "SkyEar/0.1")
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val list = JSONObject(body).optJSONArray("ac") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            if (!a.has("lat") || !a.has("lon")) continue
            val alat = a.getDouble("lat")
            val alon = a.getDouble("lon")
            val res = FloatArray(2)
            Location.distanceBetween(lat, lon, alat, alon, res)
            val o = JSONObject()
                .put("flight", a.optString("flight", "").trim())
                .put("type", a.optString("t", ""))
                .put("reg", a.optString("r", ""))
                .put("hex", a.optString("hex", ""))
                .put("altFt", a.opt("alt_baro"))
                .put("gs", a.optDouble("gs", -1.0))
                .put("lat", alat)
                .put("lon", alon)
                .put("distM", res[0].toDouble())
                .put("bearing", ((res[1] + 360f) % 360f).toDouble())
            if (a.has("track")) o.put("track", a.optDouble("track", 0.0))
            out.put(o)
        }
        return out
    }

    private fun fetchOverhead(radiusNm: Int) {
        Thread {
            try {
                val loc = getFix()
                    ?: throw Exception("No location fix. Turn on location services and try again.")
                setDeclinationFrom(loc)
                val out = queryAircraft(loc.latitude, loc.longitude, radiusNm)
                sendToPage("onOverhead", JSONObject().put("ac", out).toString())
            } catch (e: Exception) {
                sendToPage("onOverheadError", "Could not get aircraft: ${e.message}")
            }
        }.start()
    }

    // ---------- recording ----------

    private fun startRecordingUi(radiusNm: Int) {
        if (recording) return
        recRadius = radiusNm.coerceIn(5, 100)
        if (hasAudio() && hasLocation()) {
            beginRecording()
        } else {
            recPermLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun beginRecording() {
        if (recording) return
        recording = true
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val st = RecState("clip_$stamp", System.currentTimeMillis())
        val sr = 44100
        val radius = recRadius
        val wav = File(clipsDir(), "${st.id}.wav")
        web.keepScreenOn = true
        declination = null
        if (!startOrientation()) {
            sendToPage("onOrientError", "No compass sensor found, so pointing will not be recorded.")
        }

        val audioT = Thread { recordAudio(st, wav, sr, 120) }
        val snapT = Thread { collectSnapshots(st, radius) }
        audioT.start()
        snapT.start()
        Thread {
            audioT.join()
            recording = false
            snapT.join()
            finishRecording(st, sr, radius)
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(source: Int, sr: Int, bufBytes: Int): AudioRecord? {
        return try {
            val r = AudioRecord(
                source, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
            if (r.state == AudioRecord.STATE_INITIALIZED) {
                r
            } else {
                r.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun recordAudio(st: RecState, file: File, sr: Int, maxSec: Int) {
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = maxOf(minBuf, sr / 2 * 2) * 2
        // Prefer the unprocessed source (no auto-gain or noise suppression); fall back to the normal mic.
        var rec = openRecord(MediaRecorder.AudioSource.UNPROCESSED, sr, bufBytes)
        if (rec == null) {
            st.source = "MIC"
            rec = openRecord(MediaRecorder.AudioSource.MIC, sr, bufBytes)
        }
        if (rec == null) {
            st.error = "Could not open the microphone."
            return
        }
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.setLength(0)
            raf.write(ByteArray(44))
            val shorts = ShortArray(sr / 10)
            val maxBytes = maxSec.toLong() * sr * 2
            var lastLevel = 0L
            rec.startRecording()
            sendToPage("onRecState", "started")
            while (recording && st.totalBytes < maxBytes) {
                val n = rec.read(shorts, 0, shorts.size)
                if (n < 0) {
                    st.error = "Audio read error $n"
                    break
                }
                if (n == 0) continue
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                var peak = 0
                for (i in 0 until n) {
                    val v = shorts[i].toInt()
                    bb.putShort(shorts[i])
                    val a = if (v < 0) -v else v
                    if (a > peak) peak = a
                }
                raf.write(bb.array(), 0, n * 2)
                st.totalBytes += (n * 2).toLong()
                val now = System.currentTimeMillis()
                if (now - lastLevel > 200) {
                    lastLevel = now
                    sendToPage("onRecLevel", String.format(Locale.US, "%.3f", peak / 32768.0))
                }
            }
        } catch (e: Exception) {
            st.error = e.message ?: "Recording failed."
        } finally {
            try {
                rec.stop()
            } catch (e: Exception) {
                // already stopped
            }
            rec.release()
            writeWavHeader(raf, st.totalBytes, sr)
            raf.close()
        }
    }

    // Sends the phone's rotation matrix to the page about 10 times a second; the page works out the direction.
    private fun startOrientation(): Boolean {
        if (sensorListener != null) return true
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return false
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                val e = event ?: return
                val now = System.currentTimeMillis()
                if (now - lastOrientSent < 100) return
                lastOrientSent = now
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, e.values)
                val arr = JSONArray()
                for (v in r) arr.put(v.toDouble())
                val o = JSONObject().put("r", arr).put("t", now).put("a", sensorAcc)
                val d = declination
                if (d != null) o.put("d", d.toDouble())
                sendToPage("onOrient", o.toString())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                sensorAcc = accuracy
            }
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        sensorListener = l
        return true
    }

    private fun setDeclinationFrom(loc: Location) {
        try {
            declination = GeomagneticField(
                loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()
            ).declination
        } catch (e: Exception) {
            // leave declination unknown
        }
    }

    // Looks up true-north correction once a location is available (needed outside recording too).
    private fun primeDeclination() {
        if (declination != null || !hasLocation()) return
        Thread {
            try {
                getFix()?.let { setDeclinationFrom(it) }
            } catch (e: Exception) {
                // ignore; the page falls back to magnetic north
            }
        }.start()
    }

    private fun stopOrientation() {
        val l = sensorListener ?: return
        (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.unregisterListener(l)
        sensorListener = null
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataLen: Long, sr: Int) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray())
        h.putInt((36 + dataLen).toInt())
        h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray())
        h.putInt(16)
        h.putShort(1)
        h.putShort(1)
        h.putInt(sr)
        h.putInt(sr * 2)
        h.putShort(2)
        h.putShort(16)
        h.put("data".toByteArray())
        h.putInt(dataLen.toInt())
        raf.seek(0)
        raf.write(h.array())
    }

    // Takes an aircraft snapshot right away, then every 15 s, then once more after recording stops.
    private fun collectSnapshots(st: RecState, radius: Int) {
        val loc = try {
            if (hasLocation()) getFix() else null
        } catch (e: Exception) {
            null
        }
        if (loc == null) return
        st.lat = loc.latitude
        st.lon = loc.longitude
        st.acc = loc.accuracy
        try {
            declination = GeomagneticField(
                loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()
            ).declination
        } catch (e: Exception) {
            // leave declination unknown
        }

        fun snap() {
            val o = JSONObject().put("t", System.currentTimeMillis())
            try {
                o.put("ac", queryAircraft(loc.latitude, loc.longitude, radius))
            } catch (e: Exception) {
                o.put("error", e.message ?: "error")
            }
            synchronized(st.snaps) { st.snaps.put(o) }
        }

        snap()
        while (recording) {
            var waited = 0
            while (recording && waited < 15000) {
                Thread.sleep(500)
                waited += 500
            }
            if (recording) snap()
        }
        snap()
    }

    private fun finishRecording(st: RecState, sr: Int, radius: Int) {
        runOnUiThread {
            web.keepScreenOn = false
            if (!compassWanted) stopOrientation()
        }
        val wav = File(clipsDir(), "${st.id}.wav")
        if (st.totalBytes <= 0L) {
            wav.delete()
            sendToPage("onRecState", "error:" + (st.error ?: "Nothing was recorded."))
            return
        }
        try {
            val m = JSONObject()
                .put("id", st.id)
                .put("startMs", st.startMs)
                .put("durationMs", st.totalBytes * 1000L / (sr * 2L))
                .put("sampleRate", sr)
                .put("source", st.source)
                .put("note", "")
                .put("radiusNm", radius)
            val la = st.lat
            val lo = st.lon
            if (la != null && lo != null) {
                m.put("lat", la)
                m.put("lon", lo)
                val acc = st.acc
                if (acc != null) m.put("accM", acc.toDouble())
            }
            synchronized(st.snaps) { m.put("snaps", st.snaps) }
            File(clipsDir(), "${st.id}.json").writeText(m.toString())
            sendToPage("onClipSaved", st.id)
        } catch (e: Exception) {
            sendToPage("onRecState", "error:Could not save the clip: ${e.message}")
        }
    }

    // ---------- Remote ID scanning ----------

    private fun has(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun startRidUi() {
        if (ridOn) return
        val need = ArrayList<String>()
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31 && !has("android.permission.BLUETOOTH_SCAN")) need.add("android.permission.BLUETOOTH_SCAN")
        if (Build.VERSION.SDK_INT >= 33 && !has("android.permission.NEARBY_WIFI_DEVICES")) need.add("android.permission.NEARBY_WIFI_DEVICES")
        if (need.isEmpty()) startRidScan() else ridPermLauncher.launch(need.toTypedArray())
    }

    private fun startRidScan() {
        if (ridOn) return
        val loc = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val bleOk = loc && (Build.VERSION.SDK_INT < 31 || has("android.permission.BLUETOOTH_SCAN"))
        val wifiOk = loc && (Build.VERSION.SDK_INT < 33 || has("android.permission.NEARBY_WIFI_DEVICES"))
        if (!bleOk && !wifiOk) {
            sendToPage("onRidState", "error:Permission was denied. Scanning needs location plus Bluetooth or nearby devices access.")
            return
        }
        ridOn = true
        synchronized(ridLock) { ridObs = JSONArray() }
        val notes = ArrayList<String>()
        notes.add(if (bleOk && startBle()) "Bluetooth on" else "Bluetooth off or unavailable")
        notes.add(if (wifiOk && startWifi()) "Wi-Fi on (slow, about every 30 s)" else "Wi-Fi off or unavailable")
        startRidThreads()
        sendToPage("onRidState", "started")
        sendToPage("onRidState", "info:" + notes.joinToString(", ") + ".")
    }

    private fun addObs(addr: String, src: String, rssi: Int, data: ByteArray, skip: Int) {
        val hex = StringBuilder()
        for (i in skip until data.size) hex.append(String.format("%02x", data[i]))
        val o = JSONObject()
            .put("a", addr)
            .put("s", src)
            .put("r", rssi)
            .put("t", System.currentTimeMillis())
            .put("h", hex.toString())
        synchronized(ridLock) {
            if (ridObs.length() < 500) ridObs.put(o)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBle(): Boolean {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = bm.adapter ?: return false
        if (!adapter.isEnabled) return false
        val scanner = adapter.bluetoothLeScanner ?: return false
        val uuid = ParcelUuid.fromString("0000fffa-0000-1000-8000-00805f9b34fb")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val r = result ?: return
                val data = r.scanRecord?.getServiceData(uuid) ?: return
                if (data.size < 3 || data[0] != 0x0D.toByte()) return
                addObs(r.device.address, "ble", r.rssi, data, 1)
            }
        }
        val b = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= 26) b.setLegacy(false)
        return try {
            scanner.startScan(null, b.build(), cb)
            bleCallback = cb
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun startWifi(): Boolean {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        if (!wm.isWifiEnabled) return false
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                harvestWifi(wm)
            }
        }
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(rcv, f)
        }
        wifiReceiver = rcv
        Thread {
            while (ridOn) {
                try {
                    wm.startScan()
                } catch (e: Exception) {
                    // throttled or not allowed
                }
                var waited = 0
                while (ridOn && waited < 30000) {
                    Thread.sleep(500)
                    waited += 500
                }
            }
        }.start()
        return true
    }

    // Wi-Fi Beacon Remote ID is carried in a vendor element (OUI FA:0B:BC, type 0x0D). Needs Android 11+.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun harvestWifi(wm: WifiManager) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            for (sr in wm.scanResults) {
                val ies = sr.informationElements ?: continue
                for (ie in ies) {
                    if (ie.id != 221) continue
                    val bb = ie.bytes.duplicate()
                    bb.rewind()
                    val arr = ByteArray(bb.remaining())
                    bb.get(arr)
                    if (arr.size < 6 || arr[0] != 0xFA.toByte() || arr[1] != 0x0B.toByte() ||
                        arr[2] != 0xBC.toByte() || arr[3] != 0x0D.toByte()
                    ) continue
                    addObs(sr.BSSID ?: "wifi", "wifi", sr.level, arr, 4)
                }
            }
        } catch (e: Exception) {
            // ignore unreadable results
        }
    }

    // One thread sends batches to the page every second; another keeps the phone location fresh.
    private fun startRidThreads() {
        Thread {
            while (ridOn) {
                Thread.sleep(1000)
                val out = synchronized(ridLock) {
                    val c = ridObs
                    ridObs = JSONArray()
                    c
                }
                if (out.length() > 0) {
                    val p = JSONObject().put("obs", out)
                    val la = ridLat
                    val lo = ridLon
                    if (la != null && lo != null) {
                        p.put("lat", la)
                        p.put("lon", lo)
                    }
                    sendToPage("onRidBatch", p.toString())
                }
            }
        }.start()
        Thread {
            while (ridOn) {
                try {
                    val fix = getFix()
                    if (fix != null) {
                        ridLat = fix.latitude
                        ridLon = fix.longitude
                    }
                } catch (e: Exception) {
                    // keep the last known location
                }
                var waited = 0
                while (ridOn && waited < 20000) {
                    Thread.sleep(500)
                    waited += 500
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun stopRidScan(msg: String?) {
        if (!ridOn) return
        ridOn = false
        try {
            val cb = bleCallback
            if (cb != null) {
                val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bm?.adapter?.bluetoothLeScanner?.stopScan(cb)
            }
        } catch (e: Exception) {
            // already stopped
        }
        bleCallback = null
        try {
            val r = wifiReceiver
            if (r != null) unregisterReceiver(r)
        } catch (e: Exception) {
            // not registered
        }
        wifiReceiver = null
        if (msg != null) sendToPage("onRidState", msg)
    }

    // Scanning only runs while the app is on screen.
    override fun onStop() {
        super.onStop()
        stopRidScan("stopped")
        if (!recording) stopOrientation()
    }

    override fun onResume() {
        super.onResume()
        if (compassWanted) startOrientation()
    }

    override fun onDestroy() {
        stopRidScan(null)
        stopOrientation()
        recording = false
        super.onDestroy()
    }

    // ---------- export ----------

    private fun exportToDownloads(id: String) {
        if (Build.VERSION.SDK_INT < 29) {
            sendToPage("onExport", "Export needs Android 10 or newer.")
            return
        }
        Thread {
            try {
                for ((ext, mime) in listOf("wav" to "audio/wav", "json" to "application/json")) {
                    val src = File(clipsDir(), "$id.$ext")
                    if (!src.exists()) continue
                    val values = ContentValues()
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "$id.$ext")
                    values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    values.put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/SkyEar"
                    )
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Could not create the file")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }
                sendToPage("onExport", "Saved to Downloads/SkyEar")
            } catch (e: Exception) {
                sendToPage("onExport", "Export failed: ${e.message}")
            }
        }.start()
    }
}
