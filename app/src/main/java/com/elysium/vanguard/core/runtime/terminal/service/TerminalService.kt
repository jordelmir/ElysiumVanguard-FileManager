package com.elysium.vanguard.core.runtime.terminal.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.elysium.vanguard.MainActivity
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground-service guardian for sessions owned by [TerminalSessionManager].
 *
 * It deliberately does not receive commands, rootfs paths, or terminal bytes
 * through an Intent. The manager is the only process-local authority that can
 * create a PTY; this unexported service merely keeps already-created work
 * alive and gives the user a visible, one-tap Stop control.
 */
@AndroidEntryPoint
class TerminalService : Service() {

    @Inject lateinit var sessionManager: TerminalSessionManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foregroundSessionId: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        serviceScope.launch {
            sessionManager.activeSessionIds.collectLatest { activeIds ->
                if (activeIds.isEmpty()) {
                    foregroundSessionId = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val currentForeground = foregroundSessionId
                if (currentForeground == null || currentForeground !in activeIds) {
                    foregroundSessionId = activeIds.first()
                }
                val sessionId = foregroundSessionId
                if (sessionId != null) {
                    startForegroundFor(sessionId, activeIds.size)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROMOTE_SESSION -> {
                val id = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val session = sessionManager.start(id) ?: run {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                foregroundSessionId = session.id
                startForegroundFor(session.id, sessionManager.activeSessionIds.value.size.coerceAtLeast(1))
            }
            ACTION_STOP_SESSION -> {
                intent.getStringExtra(EXTRA_SESSION_ID)?.let(sessionManager::close)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundFor(sessionId: String, activeCount: Int) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, TerminalService::class.java).apply {
            action = ACTION_STOP_SESSION
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            sessionId.hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sessionLabel = if (activeCount == 1) "Linux session is running" else "$activeCount Linux sessions are running"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Elysium Runtime")
            .setContentText(sessionLabel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Linux runtime sessions",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "elysium_terminal_session"
        private const val NOTIFICATION_ID = 0xE15E10
        private const val REQUEST_OPEN = 0xE15E11

        const val ACTION_PROMOTE_SESSION = "com.elysium.vanguard.terminal.PROMOTE"
        const val ACTION_STOP_SESSION = "com.elysium.vanguard.terminal.STOP"
        const val EXTRA_SESSION_ID = "session_id"

        /** Promotes an already-created, same-process session to foreground work. */
        fun promote(context: Context, sessionId: String) {
            val intent = Intent(context.applicationContext, TerminalService::class.java).apply {
                action = ACTION_PROMOTE_SESSION
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.applicationContext.startForegroundService(intent)
            } else {
                context.applicationContext.startService(intent)
            }
        }

        /** User-initiated stop; no third party can call this service because it is unexported. */
        fun stop(context: Context, sessionId: String) {
            context.applicationContext.startService(
                Intent(context.applicationContext, TerminalService::class.java).apply {
                    action = ACTION_STOP_SESSION
                    putExtra(EXTRA_SESSION_ID, sessionId)
                }
            )
        }
    }
}
