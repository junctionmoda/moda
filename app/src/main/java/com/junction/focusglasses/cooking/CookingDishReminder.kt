package com.junction.focusglasses.cooking

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/** An inexact local reminder that survives closing the app; Android may defer it for battery health. */
object CookingDishReminder {
  private const val ACTION = "com.junction.focusglasses.COOKING_DISHES_REMINDER"
  private const val CHANNEL = "cooking-reminders"
  private const val ID = 22

  fun schedule(context: Context, delayMs: Long) {
    val pending = PendingIntent.getBroadcast(context, ID, Intent(context, CookingDishReminderReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val alarm = context.getSystemService(AlarmManager::class.java)
    // Inexact scheduling avoids the exact-alarm permission; this is a gentle reminder, not a timer.
    alarm.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, 10 * 60 * 1000L, pending)
  }

  fun notify(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Cooking reminders", NotificationManager.IMPORTANCE_DEFAULT))
    manager.notify(ID, android.app.Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle("Dishes reminder").setContentText("When you are ready, wash the dishes from your meal.").setAutoCancel(true).build())
  }
}

class CookingDishReminderReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    CookingDishReminder.notify(context)
  }
}
