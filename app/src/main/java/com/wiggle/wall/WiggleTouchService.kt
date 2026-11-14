package com.wiggle.wall

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.io.File
import kotlin.math.max

// Lock screen e selected area er upor chhoto transparent touch layer boshay,
// touch ke wallpaper e pathay. Shudhu keyguard locked thakle chole.
class WiggleTouchService : AccessibilityService() {
    private var view: View? = null
    private val h = Handler(Looper.getMainLooper())
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_ON -> h.postDelayed({ show() }, 300)
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT -> hide()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(rcv, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
        })
        show()
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { hide(); runCatching { unregisterReceiver(rcv) }; super.onDestroy() }

    private fun ours(): Boolean = runCatching {
        val wmgr = WallpaperManager.getInstance(this)
        val info = if (Build.VERSION.SDK_INT >= 34) wmgr.getWallpaperInfo(WallpaperManager.FLAG_LOCK) else wmgr.wallpaperInfo
        info?.packageName == packageName
    }.getOrDefault(true)

    private fun show() {
        if (view != null) return
        if (!(getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) return
        if (!ours()) return
        val f = File(filesDir, "bg.jpg"); if (!f.exists()) return
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outHeight <= 0) return
        val ia = o.outWidth.toFloat() / o.outHeight
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels.toFloat(); val hh = dm.heightPixels.toFloat(); val sa = w / hh
        val scx: Float; val scy: Float
        if (sa > ia) { scx = 1f; scy = ia / sa } else { scx = sa / ia; scy = 1f }
        val p = getSharedPreferences("w", 0)
        val cx = p.getFloat("cx", .5f); val cy = p.getFloat("cy", .5f); val r = p.getFloat("r", .15f)
        val px = ((cx - .5f) / scx + .5f) * w
        val py = ((cy - .5f) / scy + .5f) * hh
        val minPx = 48f * dm.density
        val halfW = max(r / ia / scx * w * 1.3f, minPx / 2)
        val halfH = max(r / scy * hh * 1.3f, minPx / 2)

        val v = View(this)
        v.setOnTouchListener { _, e -> WiggleBus.send(e.actionMasked, e.rawX, e.rawY); true }
        val lp = WindowManager.LayoutParams(
            (halfW * 2).toInt(), (halfH * 2).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (px - halfW).toInt(); y = (py - halfH).toInt() }
        runCatching { wm.addView(v, lp); view = v }
    }

    private fun hide() {
        h.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }; view = null
    }
}
