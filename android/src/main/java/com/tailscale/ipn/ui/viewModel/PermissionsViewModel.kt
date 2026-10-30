// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.TaildropDirectoryStore
import com.tailscale.ipn.ui.notifier.TransferProgressNotifier
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PermissionsViewModel : ViewModel() {
  private val _currentDir =
      MutableStateFlow<String?>(TaildropDirectoryStore.loadSavedDir()?.toString())
  val currentDir: StateFlow<String?> = _currentDir

  private val _liveTransferNotifications =
      MutableStateFlow(App.get().isLiveTransferNotificationEnabled())
  val liveTransferNotifications: StateFlow<Boolean> = _liveTransferNotifications

  fun refreshCurrentDir() {
    val newUri = TaildropDirectoryStore.loadSavedDir()?.toString()
    TSLog.d("PermissionsViewModel", "refreshCurrentDir: $newUri")
    _currentDir.value = newUri
  }

  fun toggleLiveTransferNotifications(enabled: Boolean) {
    TSLog.d("PermissionsViewModel", "toggleLiveTransferNotifications: $enabled")
    App.get().setLiveTransferNotificationEnabled(enabled)
    _liveTransferNotifications.value = enabled
    TransferProgressNotifier.onPreferenceChanged()
  }
}
