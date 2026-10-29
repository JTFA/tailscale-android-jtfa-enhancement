// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.R
import kotlin.math.max

/** Green used for "active" and for the received half of the traffic donut. */
internal val ActiveGreen = Color(0xFF2E9E5B)

/** Orange used for the sent half of the traffic donut. */
internal val SentOrange = Color(0xFFE8710A)

/** Small colored dot that fronts the status headline. */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier) {
  val color = if (active) ActiveGreen else MaterialTheme.colorScheme.outline
  Canvas(modifier.size(10.dp)) { drawCircle(color) }
}

/** "Label   Value" block, used for the Hosts/Port/Auth and Rules/Rule count lines. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
  Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.widthIn(min = 84.dp),
    )
    Text(text = value, style = MaterialTheme.typography.bodyMedium)
  }
}

/**
 * Two arc donut: received in green, sent in orange, drawn on a muted track. The arcs only reflect
 * the split of the totals, which is all the proxy reports.
 */
@Composable
fun TrafficDonut(received: Long, sent: Long, modifier: Modifier = Modifier) {
  val total = max(received + sent, 1L)
  val receivedSweep = 360f * received / total
  val sentSweep = 360f * sent / total
  val track = MaterialTheme.colorScheme.surfaceVariant

  Canvas(modifier.size(78.dp)) {
    val stroke = 10.dp.toPx()
    val inset = stroke / 2
    val arcSize = Size(size.width - stroke, size.height - stroke)
    drawArc(
        color = track,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = Stroke(width = stroke),
    )
    var start = -90f
    if (receivedSweep > 6f) {
      drawArc(
          color = ActiveGreen,
          startAngle = start + 2f,
          sweepAngle = receivedSweep - 4f,
          useCenter = false,
          topLeft = Offset(inset, inset),
          size = arcSize,
          style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
    }
    start += receivedSweep
    if (sentSweep > 6f) {
      drawArc(
          color = SentOrange,
          startAngle = start + 2f,
          sweepAngle = sentSweep - 4f,
          useCenter = false,
          topLeft = Offset(inset, inset),
          size = arcSize,
          style = Stroke(width = stroke, cap = StrokeCap.Round),
      )
    }
  }
}

/** One titled column of label/value figures. */
@Composable
fun MetricColumn(
    title: String,
    rows: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
  Column(modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
    rows.forEach { (label, value) -> StatLine(label = label, value = value) }
  }
}

@Composable
private fun StatLine(label: String, value: String) {
  Row(modifier = Modifier.padding(top = 2.dp)) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(58.dp),
    )
    Text(text = value, style = MaterialTheme.typography.bodySmall)
  }
}

/**
 * Protocol row with a letter badge, name and port, plus the switch that starts or stops that
 * listener. Tapping the row edits the port.
 */
@Composable
fun ProtocolRow(
    letter: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier =
            Modifier.size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ),
        contentAlignment = Alignment.Center,
    ) {
      Text(
          text = letter,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
    Spacer(modifier = Modifier.width(14.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
      )
      Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = checked, onCheckedChange = onCheckedChange)
  }
}

/** Big full width action button used at the bottom of the proxy page. */
@Composable
fun WideActionButton(text: String, onClick: () -> Unit) {
  androidx.compose.material3.Button(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(text = text, style = MaterialTheme.typography.titleSmall, fontSize = 16.sp)
  }
}

internal fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = arrayOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024
  var unit = 0
  while (value >= 1024 && unit < units.size - 1) {
    value /= 1024
    unit++
  }
  return String.format("%.1f %s", value, units[unit])
}

internal fun formatRate(bytesPerSecond: Long): String {
  if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
  val units = arrayOf("KB/s", "MB/s", "GB/s")
  var value = bytesPerSecond.toDouble() / 1024
  var unit = 0
  while (value >= 1024 && unit < units.size - 1) {
    value /= 1024
    unit++
  }
  return String.format("%.1f %s", value, units[unit])
}
