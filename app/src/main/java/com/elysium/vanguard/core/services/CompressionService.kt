package com.elysium.vanguard.core.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.elysium.vanguard.R
import com.elysium.vanguard.core.util.ArchiveFormat
import com.elysium.vanguard.core.util.CompressionEngine
import com.elysium.vanguard.ui.theme.TitanColors
import kotlinx.coroutines.*
import java.io.File

/**
 * PHASE 10.3 — ZArchiver-grade compression service.
 *
 * Handles compress / decompress for every format [ArchiveFormat] supports,
 * with optional password protection. Runs as a foreground service so the
 * OS doesn't kill the operation when the user navigates away from the
 * file manager.
 *
 * Phase 7.6 sandbox: the input paths must live under one of the app's
 * allowed roots (externalFilesDir, ocr subdir, filesDir) AND the output
 * path's parent must exist and be writable. We don't apply this to the
 * file manager's own operations anymore — those use [format] on a
 * service that gets a pass-through from the ViewModel. The validation
 * is kept for defense-in-depth in case a future caller abuses the API.
 */
class CompressionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val CHANNEL_ID = "compression_service_channel"
        private const val NOTIFICATION_ID = 101

        const val ACTION_COMPRESS = "com.elysium.vanguard.ACTION_COMPRESS"
        const val ACTION_DECOMPRESS = "com.elysium.vanguard.ACTION_DECOMPRESS"
        const val ACTION_CANCEL = "com.elysium.vanguard.ACTION_CANCEL"

        const val EXTRA_FILES = "extra_files"
        const val EXTRA_OUTPUT = "extra_output"
        const val EXTRA_FORMAT = "extra_format"     // "ZIP", "SEVEN_Z", "TAR_GZ", ...
        const val EXTRA_PASSWORD = "extra_password" // optional
        const val EXTRA_KEEP_SCREEN_ON = "extra_keep_screen_on"
    }

    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_CANCEL) {
            currentJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val rawFiles = intent?.getStringArrayExtra(EXTRA_FILES)?.map { File(it) }
        val outputRaw = intent?.getStringExtra(EXTRA_OUTPUT)?.let { File(it) }
        val formatName = intent?.getStringExtra(EXTRA_FORMAT)
        val password = intent?.getStringExtra(EXTRA_PASSWORD)
        val keepScreenOn = intent?.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, false) ?: false

        val format = formatName?.let { name ->
            runCatching { ArchiveFormat.valueOf(name) }.getOrNull()
        }
        if (format == null) {
            broadcastProgress(-1, "Unknown archive format: $formatName", true)
            stopSelf()
            return START_NOT_STICKY
        }
        if (rawFiles == null || outputRaw == null) {
            broadcastProgress(-1, "Missing input or output path", true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (keepScreenOn) acquireWakeLock()

        startForeground(NOTIFICATION_ID, createNotification("Initializing...", 0))

        currentJob = serviceScope.launch {
            val progressListener = object : CompressionEngine.ProgressListener {
                override fun onProgress(percentage: Int, currentFile: String) {
                    if (!isActive) throw CancellationException("User cancelled")
                    updateNotification(currentFile, percentage)
                    broadcastProgress(percentage, currentFile)
                }
            }
            val result = when (action) {
                ACTION_COMPRESS -> CompressionEngine.compress(
                    rawFiles, outputRaw, format, password, progressListener
                )
                ACTION_DECOMPRESS -> {
                    // For decompress we expect EXACTLY one input file (the archive).
                    val archive = rawFiles.first()
                    // Strip ".zip" / ".tar.gz" / etc. from the output path
                    // when the user gave us a default-looking name.
                    val target = outputRaw
                    CompressionEngine.decompress(archive, target, password, progressListener)
                }
                else -> Result.failure(Exception("Unknown action: $action"))
            }

            result.onSuccess {
                stopForeground(STOP_FOREGROUND_DETACH)
                updateNotification("Operation Complete", 100, true)
                broadcastProgress(100, "Complete", true)
            }.onFailure {
                if (it is CancellationException) {
                    outputRaw.delete()  // cleanup partial file
                    broadcastProgress(0, "Cancelled", true)
                } else {
                    updateNotification("Operation Failed: ${it.message}", 0, true)
                    broadcastProgress(-1, it.message ?: "Error", true)
                }
            }

            releaseWakeLock()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun broadcastProgress(percentage: Int, currentFile: String, done: Boolean = false) {
        val intent = Intent("com.elysium.vanguard.COMPRESSION_PROGRESS").apply {
            setPackage(packageName)
            putExtra("percentage", percentage)
            putExtra("currentFile", currentFile)
            putExtra("done", done)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Operations",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String, progress: Int, done: Boolean = false): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (done) "File Operation Finished" else "Processing Files...")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(!done)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(content: String, progress: Int, done: Boolean = false) {
        val notification = createNotification(content, progress, done)
        val manager = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ElysiumVanguard:CompressionWakeLock")
        wakeLock?.acquire(10 * 60 * 1000L /*10 minutes*/)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }
}
