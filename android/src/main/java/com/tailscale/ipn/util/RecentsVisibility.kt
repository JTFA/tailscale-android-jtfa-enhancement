// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.util

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/**
 * Applies the "Hide from recent tasks" preference to an activity's task.
 *
 * The exclusion is a property of the task record, so it has to be (re)applied every time an
 * activity becomes visible; it is reset when the task is recreated.
 */
object RecentsVisibility {
  private const val TAG = "RecentsVisibility"

  fun apply(activity: Activity, hide: Boolean) {
    try {
      val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
      am.appTasks.firstOrNull { it.taskInfo.taskId == activity.taskId }?.setExcludeFromRecents(hide)
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to update recent task visibility: $e")
    }
  }
}
