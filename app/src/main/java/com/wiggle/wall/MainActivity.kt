package com.wiggle.wall

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.min

// iOS-style palette
private const val IOS_BG = 0xFFF2F2F7.toInt()
private const val IOS_CARD = 0xFFFFFFFF.toInt()
private const val IOS_BLUE = 0xFF007AFF.toInt()
private const val IOS_LABEL = 0xFF000000.toInt()
private const val IOS_SECONDARY = 0xFF8E8E93.toInt()
private const val IOS_FILL = 0xFFE5E5EA.toInt()
private const val IOS_SEP = 0xFFD1D1D6.toInt()

private fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + .5f).toInt()

private fun roundBg(color: Int, radiusPx: Int) = GradientDrawable().apply {
    setColor(color); cornerRadius = radiusPx.toFloat()
}

class PV(c: Context) : View(c) {
    var bmp: Bitmap? = null
    var cx = .5f; var cy = .5f; var r = .15f
    private val d = c.resources.displayMetrics.density
    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f * d; color = Color.WHITE }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = IOS_BLUE }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IOS_FILL }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IOS_SECONDARY; textSize = 17f * d; textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val dst = RectF()

    fun loadBmp() {
        val f = File(context.filesDir, "bg.jpg"); if (!f.exists()) return
        bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }); invalidate()
    }

    override fun onDraw(c: Canvas) {
        val rad = 16f * d
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val b = bmp
        if (b == null) {
            c.drawText("No photo selected", width / 2f, height / 2f, hint)
        } else {
            val s = min(width / b.width.toFloat(), height / b.height.toFloat())
            val w = b.width * s; val h = b.height * s
            dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            c.drawBitmap(b, null, dst, null)
            c.drawCircle(dst.left + cx * w, dst.top + cy * h, r * h, ringOuter)
            c.drawCircle(dst.left + cx * w, dst.top + cy * h, r * h, ringInner)
        }
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null || dst.width() <= 0f) return true
        parent?.requestDisallowInterceptTouchEvent(true)
        cx = ((e.x - dst.left) / dst.width()).coerceIn(0f, 1f)
        cy = ((e.y - dst.top) / dst.height()).coerceIn(0f, 1f)
        invalidate(); return true
    }

    fun save() { context.getSharedPreferences("w", 0).edit().putFloat("cx", cx).putFloat("cy", cy).putFloat("r", r).apply() }
}

class MainActivity : Activity() {
    private lateinit var pv: PV

    private fun pressFx(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.alpha = .55f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.alpha = 1f
            }
            false
        }
    }

    private fun iosButton(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (filled) Color.WHITE else IOS_BLUE)
        background = roundBg(if (filled) IOS_BLUE else 0x1A007AFF, dp(14))
        stateListAnimator = null
        elevation = 0f
        pressFx(this)
        setOnClickListener { onClick() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        window.setBackgroundDrawable(ColorDrawable(IOS_BG))
        window.statusBarColor = IOS_BG
        window.navigationBarColor = IOS_BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val prefs = getSharedPreferences("w", 0)
        pv = PV(this).apply {
            cx = prefs.getFloat("cx", .5f); cy = prefs.getFloat("cy", .5f); r = prefs.getFloat("r", .15f)
            loadBmp()
        }

        val title = TextView(this).apply {
            text = "Wiggle Wallpaper"
            textSize = 32f
            setTextColor(IOS_LABEL)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val caption = TextView(this).apply {
            text = "TAP THE PHOTO TO PLACE THE WIGGLE AREA"
            textSize = 13f
            setTextColor(IOS_SECONDARY)
            setPadding(dp(16), 0, dp(16), 0)
        }

        // --- grouped list card: slider row + lock screen row ---
        val sizeLabel = TextView(this).apply { text = "Area Size"; textSize = 17f; setTextColor(IOS_LABEL) }
        val thumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Color.WHITE); setSize(dp(28), dp(28)); setStroke(dp(1), 0x26000000)
        }
        val sb = SeekBar(this).apply {
            max = 100
            progress = ((pv.r - .03f) / .3f * 100f).toInt().coerceIn(0, 100)
            progressTintList = ColorStateList.valueOf(IOS_BLUE)
            progressBackgroundTintList = ColorStateList.valueOf(IOS_FILL)
            setThumb(thumbDrawable)
            thumbOffset = 0
            splitTrack = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { pv.r = .03f + v / 100f * .3f; pv.invalidate() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val sliderRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(sizeLabel)
            addView(sb, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val separator = View(this).apply { setBackgroundColor(IOS_SEP) }
        val lockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            addView(TextView(context).apply {
                text = "Lock Screen Touch"; textSize = 17f; setTextColor(IOS_LABEL)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply {
                text = "Accessibility  ›"; textSize = 17f; setTextColor(IOS_SECONDARY)
            })
            pressFx(this)
            setOnClickListener {
                Toast.makeText(this@MainActivity, "Select Wiggle Wallpaper and turn it ON", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(IOS_CARD, dp(12))
            addView(sliderRow, LinearLayout.LayoutParams(-1, -2))
            addView(separator, LinearLayout.LayoutParams(-1, 1).apply { marginStart = dp(16) })
            addView(lockRow, LinearLayout.LayoutParams(-1, dp(52)))
        }

        val pick = iosButton("Choose Photo", false) {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1)
        }
        val save = iosButton("Set Wallpaper", true) {
            pv.save()
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this@MainActivity, WiggleService::class.java)))
        }
        val footnote = TextView(this).apply {
            text = "Choose \"Home screen and lock screen\" when setting the wallpaper."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(IOS_SECONDARY)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(IOS_BG)
            setPadding(dp(16), dp(24), dp(16), dp(16))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            addView(pv, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(caption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(16) })
            addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            addView(pick, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(10) })
            addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(12) })
            addView(footnote, LinearLayout.LayoutParams(-1, -2))
        })
    }

    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        val u = d?.data ?: return
        contentResolver.openInputStream(u)?.use { i -> File(filesDir, "bg.jpg").outputStream().use { o -> i.copyTo(o) } }
        pv.loadBmp()
    }
}
