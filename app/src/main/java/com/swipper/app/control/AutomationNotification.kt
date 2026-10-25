package com.swipper.app.control

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.swipper.app.R
import com.swipper.app.service.SwipeAccessibilityService
import com.swipper.app.ui.MainActivity

/**
 * Notification persistante de l'automatisation : affichée tant que la boucle n'est pas
 * Idle, avec l'état, les swipes du jour et les actions Pause/Reprendre et Stop.
 * Sans la permission `POST_NOTIFICATIONS` (Android 13+), rien n'est affiché et la
 * boucle fonctionne quand même.
 */
class AutomationNotification(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
    }

    /** Publie, met à jour ou retire la notification selon [state]. */
    fun update(state: AutomationState, swipesToday: Int, userPaused: Boolean) {
        if (state == AutomationState.Idle) {
            cancel()
            return
        }
        if (!canPost()) return
        val toggleLabel = if (userPaused) R.string.action_resume else R.string.action_pause
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title, context.getString(stateLabel(state))))
            .setContentText(context.getString(R.string.notification_swipes_today, swipesToday))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(toggleLabel), actionIntent(ACTION_TOGGLE_PAUSE, REQUEST_TOGGLE))
            .addAction(0, context.getString(R.string.action_stop), actionIntent(ACTION_STOP, REQUEST_STOP))
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission retirée entre-temps : la boucle continue sans notification.
        }
    }

    /** Retire la notification. */
    fun cancel() = manager.cancel(NOTIFICATION_ID)

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return manager.areNotificationsEnabled()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, AutomationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL_ID = "automation"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_TOGGLE = 1
        private const val REQUEST_STOP = 2
        private const val REQUEST_OPEN = 3
        const val ACTION_TOGGLE_PAUSE = "com.swipper.app.action.TOGGLE_PAUSE"
        const val ACTION_STOP = "com.swipper.app.action.STOP"

        /** Retire la notification sans instance (contrôleur absent). */
        fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

        /** Libellé français d'un état. */
        fun stateLabel(state: AutomationState): Int = when (state) {
            AutomationState.Idle -> R.string.loop_state_idle
            AutomationState.Running -> R.string.loop_state_running
            AutomationState.Paused -> R.string.loop_state_paused
            AutomationState.Resting -> R.string.loop_state_resting
            AutomationState.CapReached -> R.string.loop_state_cap
        }
    }
}

/** Reçoit les actions de la notification (non exporté : seules nos `PendingIntent` l'atteignent). */
class AutomationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = SwipeAccessibilityService.instance.value?.controller
        if (controller == null) {
            // Processus tué sans release() : la notification a survécu à la boucle.
            AutomationNotification.cancel(context)
            return
        }
        when (intent.action) {
            AutomationNotification.ACTION_TOGGLE_PAUSE -> controller.togglePause()
            AutomationNotification.ACTION_STOP -> controller.stop()
        }
    }
}
