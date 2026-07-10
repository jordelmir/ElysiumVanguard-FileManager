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
import com.elysium.vanguard.R
import com.elysium.vanguard.core.runtime.terminal.session.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 9.6.1 — Foreground service that owns active terminal sessions.
 *
 * The service holds onto [TerminalSession]s created by the runtime UI
 * so the shell process and its output stream survive Activity
 * recreation, rotation, and brief backgrounding. The Activity binds
 * through a static service locator instead of an [android.content.ServiceConnection]
 * because terminal sessions are inherently app-scoped — we don't
 * expose remote access from this service.
 *
 * Phase 9.6.1 hard rules:
 *
 *  - At most one foreground session per service instance. Phased 9.6.2
 *    will multiplex; right now we keep a 1:1 with the Activity that
 *    promoted it.
 *  - Service is `exported="false"` so no other app can poke a session
 *    open.
 *  - Stop is idempotent.
 *
 * Phase 9.6.1 — first build; intentionally minimal.
 */
class TerminalService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Sessions held by this service. Keyed by id; nulls not allowed. */
    private val sessions = ConcurrentHashMap<String, TerminalSession>()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        activeSessionId?.let { startForegroundIfNeeded(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                val session = TerminalSession(
                    TerminalSession.Config(
                        cols = intent.getIntExtra(EXTRA_COLS, 80),
                        rows = intent.getIntExtra(EXTRA_ROWS, 24),
                        termName = intent.getStringExtra(EXTRA_TERM_NAME) ?: "xterm-256color"
                    )
                )
                sessions[sessionId] = session
                activeSessionId = sessionId
                session.start()
                startForegroundIfNeeded(sessionId)
            }
            ACTION_STOP_SESSION -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return START_NOT_STICKY
                sessions.remove(sessionId)?.stop()
                if (activeSessionId == sessionId) {
                    activeSessionId = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_FEED_INPUT -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val bytes = intent.getByteArrayExtra(EXTRA_BYTES) ?: ByteArray(0)
                val session = sessionId?.let { sessions[it] } ?: return START_NOT_STICKY
                serviceScope.launch { session.write(bytes) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundIfNeeded(sessionId: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Elysium Terminal")
            .setContentText("Session $sessionId")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pending)
        // foregroundServiceType = "dataSync" mirrors the existing
        // CompressionService pattern and is appropriate for streaming
        // read/write from a child process. Phase 9.6.2 may add a custom
        // "terminal" type if Android gains one.
        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Terminal sessions",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        sessions.values.forEach { it.stop() }
        sessions.clear()
        activeSessionId = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "elysium_terminal_session"
        private const val NOTIFICATION_ID = 0xE15E10

        const val ACTION_START_SESSION = "com.elysium.vanguard.terminal.START"
        const val ACTION_STOP_SESSION = "com.elysium.vanguard.terminal.STOP"
        const val ACTION_FEED_INPUT = "com.elysium.vanguard.terminal.FEED"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_COLS = "cols"
        const val EXTRA_ROWS = "rows"
        const val EXTRA_TERM_NAME = "term_name"
        const val EXTRA_BYTES = "bytes"

        @Volatile
        private var activeSessionId: String? = null

        /**
         * Convenience: spawn/start a new session and start the service
         * as a foreground one. Returns the new session id.
         */
        fun start(
            context: Context,
            cols: Int = 80,
            rows: Int = 24,
            termName: String = "xterm-256color"
        ): String {
            val id = java.util.UUID.randomUUID().toString()
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_SESSION_ID, id)
                putExtra(EXTRA_COLS, cols)
                putExtra(EXTRA_ROWS, rows)
                putExtra(EXTRA_TERM_NAME, termName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return id
        }

        fun stop(context: Context, sessionId: String) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_STOP_SESSION
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            context.startService(intent)
        }

        fun feed(context: Context, sessionId: String, bytes: ByteArray) {
            val intent = Intent(context, TerminalService::class.java).apply {
                action = ACTION_FEED_INPUT
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_BYTES, bytes)
            }
            context.startService(intent)
        }
    }
}
