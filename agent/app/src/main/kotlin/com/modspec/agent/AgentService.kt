package com.modspec.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.modspec.agent.rpc.LocalHttpServer
import com.modspec.agent.rpc.LocalWsServer
import com.modspec.agent.rpc.RpcHandler

/**
 * Foreground companion service hosting the local HTTP (8764) and WebSocket (8765) RPC layer.
 */
class AgentService : Service() {

    private lateinit var rpcHandler: RpcHandler
    private var httpServer: LocalHttpServer? = null
    private var wsServer: LocalWsServer? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        rpcHandler = RpcHandler(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification())
        httpServer = LocalHttpServer(rpcHandler, RpcHandler.HTTP_PORT).also { it.start() }
        wsServer = LocalWsServer(rpcHandler, RpcHandler.WS_PORT).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REAPPLY -> rpcHandler.reapplyFromIntent(intent.getBooleanExtra(EXTRA_ONLY_FAILED, false))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        wsServer?.stop()
        httpServer?.stop()
        wsServer = null
        httpServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.agent_service_title))
            .setContentText(getString(R.string.agent_service_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel(): String {
        val channelId = CHANNEL_ID
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                getString(R.string.agent_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        return channelId
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_REAPPLY = "com.modspec.agent.action.REAPPLY"
        const val EXTRA_ONLY_FAILED = "only_failed"

        private const val CHANNEL_ID = "modspec_agent"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reapply(context: Context, onlyFailed: Boolean = false) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_REAPPLY
                putExtra(EXTRA_ONLY_FAILED, onlyFailed)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
