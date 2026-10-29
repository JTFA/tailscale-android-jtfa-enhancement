// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.tailscale.ipn.BuildConfig
import java.text.Collator
import java.util.Locale

data class InstalledApp(val name: String, val packageName: String)

class InstalledAppsManager(
    val packageManager: PackageManager,
) {
  fun fetchInstalledApps(): List<InstalledApp> {
    // Plain string order compares code points, so Chinese names land in an order that looks
    // random. The Chinese collator orders Han characters by pinyin, which is what app pickers do,
    // and it is case-insensitive on the primary level so "adb" and "Adb" sort together.
    val collator =
        Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply { strength = Collator.PRIMARY }
    return packageManager
        .getInstalledApplications(PackageManager.GET_META_DATA)
        .filter(appIsIncluded)
        .map {
          InstalledApp(
              name = it.loadLabel(packageManager).toString(),
              packageName = it.packageName,
          )
        }
        .sortedWith { a, b ->
          val byName = collator.compare(a.name, b.name)
          if (byName != 0) {
            byName
          } else {
            val exact = a.name.compareTo(b.name)
            if (exact != 0) exact else a.packageName.compareTo(b.packageName)
          }
        }
  }

  private val appIsIncluded: (ApplicationInfo) -> Boolean = { app ->
    app.packageName != BuildConfig.APPLICATION_ID &&
        // Only show apps that can access the Internet
        packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) ==
            PackageManager.PERMISSION_GRANTED
  }
}
