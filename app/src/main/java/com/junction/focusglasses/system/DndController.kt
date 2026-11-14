package com.junction.focusglasses.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Turns on the phone's Do Not Disturb (priority only) during focus, and restores it after. */
class DndController(private val context: Context) {
  private val nm = context.getSystemService(NotificationManager::class.java)
  private var previousFilter: Int? = null

  val hasAccess: Boolean get() = nm.isNotificationPolicyAccessGranted

  fun openAccessSettings() {
    context.startActivity(
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
  }

  /** Returns true if DND was switched on. */
  fun enable(): Boolean {
    if (!hasAccess) return false
    if (previousFilter == null) previousFilter = nm.currentInterruptionFilter
    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    return true
  }

  fun restore() {
    val prev = previousFilter ?: return
    if (hasAccess) nm.setInterruptionFilter(prev)
    previousFilter = null
  }
}
