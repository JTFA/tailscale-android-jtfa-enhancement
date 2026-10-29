// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R

/** A top-level destination reachable from the bottom bar. */
data class BottomTab(
    val route: String,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
)

val bottomTabs =
    listOf(
        BottomTab(route = "main", label = R.string.tab_tailscale, icon = R.drawable.ic_tab_tailscale),
        BottomTab(route = "adblock", label = R.string.tab_adblock, icon = R.drawable.ic_tab_adblock),
        BottomTab(route = "proxy", label = R.string.tab_proxy, icon = R.drawable.ic_tab_proxy),
    )

@Composable
fun AppBottomBar(currentRoute: String?, onNavigate: (String) -> Unit) {
  NavigationBar {
    bottomTabs.forEach { tab ->
      NavigationBarItem(
          selected = currentRoute == tab.route,
          onClick = { onNavigate(tab.route) },
          icon = { Icon(painterResource(tab.icon), contentDescription = null) },
          label = { Text(stringResource(tab.label), maxLines = 1) },
      )
    }
  }
}
