package com.example.gpsapp

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.os.CountDownTimer
import androidx.compose.foundation.lazy.items
import java.io.File
import java.util.*
import com.example.gpsapp.filterOutliersMad
import com.example.gpsapp.RecordedSession
import android.os.Environment
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var gpsService: UsbGpsService

    private val gpsFix = mutableStateOf<GpsFix?>(null)
    private var requestedDeviceName: String? = null
    private var isRecording by mutableStateOf(false)
    private var recordCountdown by mutableStateOf(0)
    private val recordedFixes = mutableListOf<GpsFix>()
    private val lastSavedFix = mutableStateOf<GpsFix?>(null)
    private val recordedSessions = mutableStateListOf<RecordedSession>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_USB_PERMISSION) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted && device != null && device.deviceName == requestedDeviceName) {
                    connectToGps()
                } else {
                    Toast.makeText(this@MainActivity, "USB permission denied or device mismatch", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        gpsService = UsbGpsService(this)

        registerReceiver(
            usbReceiver,
            IntentFilter(ACTION_USB_PERMISSION),
            Context.RECEIVER_NOT_EXPORTED
        )
        loadSessionsFromCsv()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("USB GPS Reader", style = MaterialTheme.typography.headlineMedium)
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(onClick = { startUsbPermissionFlow() }) {
                            Text("Start Reading")
                        }
                        Button(
                            onClick = { startRecording() },
                            enabled = !isRecording
                        ) {
                            Text("Record 30s")
                        }

                        if (isRecording) {
                            Text("Recording... ${recordCountdown}s remaining")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Live GPS Fix:", style = MaterialTheme.typography.titleMedium)
                        val fix = gpsFix.value
                        if (fix != null) {
                            Text("Latitude: %.8f".format(fix.latitude))
                            Text("Longitude: %.8f".format(fix.longitude))
                            Text("Altitude: %.3f m".format(fix.altitude))
                        } else {
                            Text("Waiting for GPS fix...")
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Saved Sessions (Today):", style = MaterialTheme.typography.titleMedium)

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 350.dp)
                        ) {
                            item {
                                Row(Modifier.padding(bottom = 8.dp)) {
                                    Text("Time", Modifier.width(130.dp))
                                    Text("Lat", Modifier.width(100.dp))
                                    Text("Lon", Modifier.width(100.dp))
                                    Text("Alt", Modifier.width(80.dp))
                                }
                            }

                            items(recordedSessions) { session ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text(session.readableTime.takeLast(8), Modifier.width(100.dp))
                                    Text("%.8f".format(session.latitude), Modifier.width(100.dp))
                                    Text("%.8f".format(session.longitude), Modifier.width(100.dp))
                                    Text("%.3f".format(session.altitude), Modifier.width(80.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = { exportCsvFile() }) {
                            Text("Export CSV")
                        }
                    }
                }
            }
        }
    }

    private fun startUsbPermissionFlow() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            val info = "Found: ${device.deviceName}, VID=${device.vendorId}, PID=${device.productId}"
        }

        for (device in deviceList.values) {
            if (device.vendorId == 5446 && device.productId == 425) {
                if (usbManager.hasPermission(device)) {
                    requestedDeviceName = device.deviceName
                    connectToGps()
                } else {
                    val permissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    requestedDeviceName = device.deviceName
                    usbManager.requestPermission(device, permissionIntent)
                }
                return
            }
        }
    }

    private fun connectToGps() {

        gpsService.connectToGps { line ->
            val trimmed = line.trim()

            val fix = GpsParser.parseGpggaSentence(trimmed)
            if (fix != null) {
                gpsFix.value = fix

                // If recording, store it
                if (isRecording) {
                    recordedFixes.add(fix)
                }
            }
        }
    }

    private fun startRecording() {
        isRecording = true
        recordCountdown = 30
        recordedFixes.clear()

        // Timer coroutine
        val timer = object : CountDownTimer(30_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                recordCountdown = (millisUntilFinished / 1000).toInt()
            }

            override fun onFinish() {
                isRecording = false
                saveRecordedFixes()
                loadSessionsFromCsv()
            }
        }
        timer.start()
    }

    private fun saveRecordedFixes() {
        if (recordedFixes.isEmpty()) return

        val lats = recordedFixes.map { it.latitude }
        val lons = recordedFixes.map { it.longitude }
        val alts = recordedFixes.map { it.altitude }

        val filteredLats = filterOutliersMad(lats)
        val filteredLons = filterOutliersMad(lons)
        val filteredAlts = filterOutliersMad(alts)

        val avgLat = filteredLats.average()
        val avgLon = filteredLons.average()
        val avgAlt = filteredAlts.average()
        val timestamp = System.currentTimeMillis()

        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dateStr = formatter.format(Date(timestamp))
        lastSavedFix.value = GpsFix(avgLat, avgLon, avgAlt)
        val readableTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val readableTime = readableTimeFormatter.format(Date(timestamp))

        val file = File(filesDir, "gps_$dateStr.csv")
        val isNew = !file.exists()

        if (isNew) {
            file.appendText("timestamp,readable_time,latitude,longitude,altitude\n")
        }
        file.appendText("$timestamp,$readableTime,$avgLat,$avgLon,$avgAlt\n")
    }

    private fun loadSessionsFromCsv() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val file = File(filesDir, "gps_$today.csv")
        if (!file.exists()) return

        val lines = file.readLines().drop(1) // Skip header
        val sessions = mutableListOf<Pair<Long, RecordedSession>>() // Pair of timestamp and session

        for (line in lines) {
            val parts = line.split(",")
            if (parts.size >= 5) {
                val timestamp = parts[0].toLongOrNull() ?: continue
                val session = RecordedSession(
                    readableTime = parts[1],
                    latitude = parts[2].toDoubleOrNull() ?: continue,
                    longitude = parts[3].toDoubleOrNull() ?: continue,
                    altitude = parts[4].toDoubleOrNull() ?: continue
                )
                sessions.add(timestamp to session)
            }
        }

        // Sort by timestamp descending
        val sortedSessions = sessions.sortedByDescending { it.first }.map { it.second }

        recordedSessions.clear()
        recordedSessions.addAll(sortedSessions)
    }

    private fun exportCsvFile() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val file = File(filesDir, "gps_$today.csv")

        if (!file.exists()) {
            Toast.makeText(this, "No CSV file to export", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Export GPS CSV"))
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        gpsService.disconnect()
        super.onDestroy()
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.usbgpsreader.USB_PERMISSION"
    }
}