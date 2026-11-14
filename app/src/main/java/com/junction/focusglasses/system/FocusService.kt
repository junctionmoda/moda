package com.junction.focusglasses.system

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.junction.focusglasses.FocusApp
import com.junction.focusglasses.MainActivity

/**
 * Keeps the session alive with the screen off (timer, camera stream, music) and listens for the
 * phone being unlocked, which counts as a distraction during focus.
 */
class FocusService : Service() {
  companion object {
    private const val CHANNEL = "focus"
    private const val NOTIFICATION_ID = 1

    fun start(context: Context) {
      try {
        context.startForegroundService(Intent(context, FocusService::class.java))
      } catch (t: Throwable) {
        Log.w("FocusGlasses:Service", "could not start foreground service", t)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, FocusService::class.java))
    }
  }

  private var wakeLock: PowerManager.WakeLock? = null

  private val unlockReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (intent.action == Intent.ACTION_USER_PRESENT) (application as FocusApp).graph.engine.onPhoneUnlocked()
        }
      }

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "专注会话", NotificationManager.IMPORTANCE_LOW))
    val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
    if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
    else registerReceiver(unlockReceiver, filter)
    wakeLock =
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FocusGlasses:session")
            .apply { acquire(3 * 60 * 60 * 1000L) }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val open =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("专注中")
            .setContentText("眼镜正在陪你专注")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    try {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
    } catch (t: Throwable) {
      Log.w("FocusGlasses:Service", "startForeground failed", t)
      stopSelf()
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    try {
      unregisterReceiver(unlockReceiver)
    } catch (_: Throwable) {}
    wakeLock?.let { if (it.isHeld) it.release() }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null
}
