// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.util.InstalledAppsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the AdBlock per-app screen. Filtering is on by default for every app; the view model stores
 * the apps the user turned off and moves apps into the tunnel when asked.
 */
class AdBlockAppsViewModel : ViewModel() {
  private val manager = InstalledAppsManager(App.get().packageManager)

  val installedApps: StateFlow<List<InstalledApp>> =
      flow { emit(manager.fetchInstalledApps()) }
          .flowOn(Dispatchers.IO)
          .stateIn(
              scope = viewModelScope,
              started = SharingStarted.WhileSubscribed(5000),
              initialValue = listOf(),
          )

  val disabled: MutableStateFlow<Set<String>> = MutableStateFlow(App.get().adblockDisabledPackages())

  /** Bumped whenever the tunnel list changes, so rows re-read their membership. */
  val membershipVersion = MutableStateFlow(0)

  fun setFiltered(packageName: String, filtered: Boolean) {
    val current = disabled.value
    val updated = if (filtered) current - packageName else current + packageName
    disabled.value = updated
    App.get().setAdblockDisabledPackages(updated)
  }

  fun addToTunnel(packageName: String) {
    App.get().addPackageToTunnel(packageName)
    membershipVersion.value++
  }
}
