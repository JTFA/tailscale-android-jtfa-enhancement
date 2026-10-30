// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.util.LocalSectionHeaderBackground
import com.tailscale.ipn.ui.viewModel.TaildropViewModel
import com.tailscale.ipn.ui.viewModel.TaildropViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Radius of the card's top corners. The dialog window background uses the same value in pixels. */
const val SHARE_SHEET_CORNER_RADIUS = 28

/**
 * Content of the share sheet card. This composable only draws; the window it lives in (its rounded
 * background, its blur and its slide) is set up by `ShareSheetDialog`, so everything here is sized
 * by its own content and never touches the window.
 *
 * [glass] switches between the two looks offered in Settings → Theme. With glass the card paints a
 * translucent gradient and a bright rim over the frosted window background; without it the window is
 * an opaque surface and nothing is painted on top.
 */
@Composable
fun ShareSheetCard(
    requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    applicationScope: CoroutineScope,
    glass: Boolean,
    onCancel: () -> Unit,
    onShareStarted: () -> Unit,
    viewModel: TaildropViewModel =
        viewModel(factory = TaildropViewModelFactory(requestedTransfers, applicationScope)),
) {
  val night = isSystemInDarkTheme()
  val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
  val context = LocalContext.current
  val files by requestedTransfers.collectAsState()

  // Section headers ("Recently used", "My devices") paint the app surface by default, which would
  // show up as opaque bars on the frosted card.
  CompositionLocalProvider(
      LocalSectionHeaderBackground provides if (glass) Color.Transparent else null
  ) {
    Box(
        modifier =
            Modifier.fillMaxWidth().then(if (glass) Modifier.glassChrome(night) else Modifier)
    ) {
      Column(
          modifier =
              Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()
      ) {
        // The title is centered on its own, whatever width the Cancel pill takes.
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
        ) {
          CancelPill(
              onClick = onCancel,
              glass = glass,
              modifier = Modifier.align(Alignment.CenterStart),
          )
          Text(
              text = stringResource(R.string.share),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.align(Alignment.Center),
          )
        }

        FileShareHeader(fileTransfers = files, totalSize = viewModel.totalSize)

        when (viewModel.state.collectAsState().value) {
          Ipn.State.Running -> {
            val recent by viewModel.recentPeers.collectAsState()
            val other by viewModel.otherPeers.collectAsState()
            FileSharePeerList(
                recent = recent,
                other = other,
                stateViewGenerator = { peerId ->
                  viewModel.TrailingContentForPeer(peerId = peerId)
                },
                onShare = { peer ->
                  // A device that cannot be reached right now leaves the sheet open: share() reports
                  // the reason with a toast and there is nothing else to show.
                  if (viewModel.share(context, peer)) {
                    onShareStarted()
                  }
                },
            )
          }
          else -> FileShareConnectView { viewModel.startVPN() }
        }
      }
    }
  }
}

/** The only way out of the sheet: a pill in the top-left corner, clear of the centered title. */
@Composable
private fun CancelPill(onClick: () -> Unit, glass: Boolean, modifier: Modifier = Modifier) {
  Surface(
      onClick = onClick,
      modifier = modifier.height(32.dp),
      shape = CircleShape,
      color =
          if (glass) Color.White.copy(alpha = 0.22f)
          else MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Box(modifier = Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
      Text(text = stringResource(R.string.cancel), style = MaterialTheme.typography.labelLarge)
    }
  }
}

/**
 * The frosted look: a soft light gradient over the blurred window background plus a rim that is
 * brightest along the top edge, the way ColorOS draws its floating panels. Clipped to the same
 * rounded top corners as the window background, so the blur region and the paint line up.
 */
private fun Modifier.glassChrome(night: Boolean): Modifier {
  val shape = RoundedCornerShape(topStart = SHARE_SHEET_CORNER_RADIUS.dp, topEnd = SHARE_SHEET_CORNER_RADIUS.dp)
  val top = if (night) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.34f)
  val bottom = if (night) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.18f)
  val rim =
      if (night)
          listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.16f))
      else
          listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.40f))
  return this
      .clip(shape)
      .background(Brush.verticalGradient(listOf(top, bottom)))
      .border(width = 1.dp, brush = Brush.linearGradient(rim), shape = shape)
}
