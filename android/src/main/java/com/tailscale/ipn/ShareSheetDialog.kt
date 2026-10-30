// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.view.SHARE_SHEET_CORNER_RADIUS
import com.tailscale.ipn.ui.view.ShareSheetCard
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The share sheet card, in a window of its own.
 *
 * A floating, translucent window is the only kind Android is willing to blur behind, and its
 * background drawable decides which part of the screen gets blurred: giving the card its own window
 * therefore frosts the card alone, and leaves everything above it as crisp as the app behind it. The
 * same window is what slides up in and down out, so the blur moves with the card.
 *
 * Where blur is not on offer (before Android 12, with the battery saver / GPU switch off, or on a ROM
 * that refuses floating windows) the window simply becomes an almost opaque surface in the same
 * rounded shape, which is the same card with the frosted look turned into a flat one.
 */
class ShareSheetDialog(
    private val host: ComponentActivity,
    private val requestedTransfers: StateFlow<List<Ipn.OutgoingFile>>,
    private val applicationScope: CoroutineScope,
    private val glass: Boolean,
    private val onShareStarted: () -> Unit,
    private val onClosed: () -> Unit,
) : Dialog(host, R.style.Theme_TSShareCard) {

  private var closing = false
  private var blurEnabled = crossWindowBlurEnabled()
  private var blurListener: Consumer<Boolean>? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    // A ComposeView inside a dialog is not in the activity's view tree, so it has to be handed the
    // activity's owners: that is what lets the card reuse the activity's ViewModels and lifecycle.
    val content =
        ComposeView(host).apply {
          setViewTreeLifecycleOwner(host)
          setViewTreeViewModelStoreOwner(host)
          setViewTreeSavedStateRegistryOwner(host)
          setContent {
            AppTheme(setSystemBars = false) {
              ShareSheetCard(
                  requestedTransfers = requestedTransfers,
                  applicationScope = applicationScope,
                  glass = glass,
                  onCancel = { requestClose() },
                  onShareStarted = onShareStarted,
              )
            }
          }
        }
    setContentView(content)

    window?.let { window ->
      window.setGravity(Gravity.BOTTOM)
      window.setDimAmount(0f)
      // The card draws all the way to the bottom edge; its content pads itself out of the way of
      // the navigation bar.
      WindowCompat.setDecorFitsSystemWindows(window, false)
      window.setLayout(MATCH_PARENT, WRAP_CONTENT)
      applyWindowBackground(window)
    }

    setCancelable(true)
    setOnCancelListener { requestClose() }
  }

  override fun onStart() {
    super.onStart()
    // Re-assert the layout: a dialog window created before its content was measured may come back
    // with the default floating size.
    window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
    registerBlurListener()

    val window = window ?: return
    window.decorView.post {
      val height = window.decorView.height
      if (height > 0 && !closing) {
        // With gravity BOTTOM, a positive y moves the window up; a negative one moves it below the
        // bottom edge, which is where the card comes from.
        slide(from = -height, to = 0, duration = SLIDE_IN_MS)
      }
    }
  }

  override fun onStop() {
    unregisterBlurListener()
    super.onStop()
  }

  /** One way out, however it was asked for: the exit is played once and then the sheet is gone. */
  fun requestClose() {
    if (closing) return
    closing = true
    val height = window?.decorView?.height ?: 0
    if (height <= 0 || !isShowing) {
      dismiss()
      onClosed()
      return
    }
    slide(from = 0, to = -height, duration = SLIDE_OUT_MS) {
      dismiss()
      onClosed()
    }
  }

  // The window background carries the blur region and its rounded corners, so both looks share the
  // same shape. Only the fill differs.
  private fun applyWindowBackground(window: Window) {
    val radius = SHARE_SHEET_CORNER_RADIUS * host.resources.displayMetrics.density
    val background =
        GradientDrawable().apply {
          shape = GradientDrawable.RECTANGLE
          // top-left, top-right, bottom-right, bottom-left
          cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
          setColor(backgroundTint())
        }
    window.setBackgroundDrawable(background)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // A ROM that refuses the blur simply leaves the window with its own fill; never a crash.
      runCatching {
        window.setBackgroundBlurRadius(if (glass && blurEnabled) BLUR_RADIUS_PX else 0)
      }
    }
  }

  private fun backgroundTint(): Int {
    val night =
        (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    return when {
      // No glass: the plain sheet, the app's own surface colour.
      !glass -> if (night) DARK_SURFACE else LIGHT_SURFACE
      // Glass with blur: just a wash, so that what is behind stays visible through the frost.
      blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
          if (night) GLASS_DARK_TINT else GLASS_LIGHT_TINT
      // Glass without blur: almost opaque, so text stays readable over whatever is behind it.
      else -> if (night) GLASS_DARK_FALLBACK else GLASS_LIGHT_FALLBACK
    }
  }

  private fun crossWindowBlurEnabled(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val windowManager = host.getSystemService(WindowManager::class.java) ?: return false
    return runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
  }

  // Blur can be switched off while the card is up (battery saver kicks in, the GPU switch flips);
  // the card then falls back to the opaque fill instead of turning into bare transparency.
  private fun registerBlurListener() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || blurListener != null) return
    val windowManager = host.getSystemService(WindowManager::class.java) ?: return
    val listener =
        Consumer<Boolean> { enabled ->
          if (enabled != blurEnabled) {
            blurEnabled = enabled
            window?.let { applyWindowBackground(it) }
          }
        }
    blurListener = listener
    windowManager.addCrossWindowBlurEnabledListener(host.mainExecutor, listener)
  }

  private fun unregisterBlurListener() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val listener = blurListener ?: return
    blurListener = null
    val windowManager = host.getSystemService(WindowManager::class.java) ?: return
    windowManager.removeCrossWindowBlurEnabledListener(listener)
  }

  /**
   * Moves the window itself, blur included, so the card and the frosted region stay together. A
   * window has no height of its own until it is laid out, hence the pixel values come from the
   * caller.
   */
  private fun slide(from: Int, to: Int, duration: Long, end: (() -> Unit)? = null) {
    val window = window
    if (window == null) {
      end?.invoke()
      return
    }
    val animator = ValueAnimator.ofInt(from, to)
    animator.duration = duration
    animator.interpolator = DecelerateInterpolator()
    animator.addUpdateListener { animation ->
      val attributes = window.attributes
      attributes.y = animation.animatedValue as Int
      window.attributes = attributes
    }
    if (end != null) {
      animator.addListener(
          object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
              end()
            }
          }
      )
    }
    animator.start()
  }

  private companion object {
    const val SLIDE_IN_MS = 220L
    const val SLIDE_OUT_MS = 180L
    // Android's own guidance for a frosted panel: strong enough to read as frost, not a smear.
    const val BLUR_RADIUS_PX = 48

    const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
    const val DARK_SURFACE = 0xFF232222.toInt()

    const val GLASS_LIGHT_TINT = 0x47FFFFFF
    const val GLASS_DARK_TINT = 0x59000000
    const val GLASS_LIGHT_FALLBACK = 0xF2FFFFFF.toInt()
    const val GLASS_DARK_FALLBACK = 0xF2232222.toInt()
  }
}
