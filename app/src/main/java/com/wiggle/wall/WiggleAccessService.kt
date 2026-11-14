package com.wiggle.wall

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Lock screen e wallpaper touch pay na. Tai lock screen cholar somoy wiggle area er upor
 * ekta transparent overlay boshiye touch gulo wallpaper engine ke pathai.
 * Overlay shudhu selected area te thake, baki screen e swipe/unlock normal kaj kore.
 */
class WiggleAccessService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var shownRect: Rect? = null
    private var registered = false

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_USER_PRESENT || i.action == Intent.ACTION_SCREEN_OFF) hide()
            handler.postDelayed({ refresh() }, 250)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        WiggleBridge.svc = this
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rcv, f)
        registered = true
        refresh()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (WiggleBridge.svc === this) WiggleBridge.svc = null
        if (registered) { try { unregisterReceiver(rcv) } catch (_: Exception) {}; registered = false }
        hide()
        super.onDestroy()
    }

    fun refresh() {
        val eng = WiggleBridge.engine
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val r = if (eng != null && locked) eng.region() else null
        if (r == null) hide() else show(r)
    }

    private fun show(r: Rect) {
        if (overlay != null && shownRect == r) return
        hide()
        val v = View(this)
        v.setOnTouchListener { _, ev -> WiggleBridge.engine?.ext(ev); true }
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = r.left; y = r.top }
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v; shownRect = Rect(r)
        } catch (_: Exception) { overlay = null; shownRect = null }
    }

    private fun hide() {
        overlay?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (_: Exception) {} }
        overlay = null; shownRect = null
    }
}
