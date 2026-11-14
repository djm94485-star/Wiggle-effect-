package com.wiggle.wall

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Bundle
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

private const val VS = "attribute vec2 a;varying vec2 v;void main(){v=vec2(a.x*.5+.5,.5-a.y*.5);gl_Position=vec4(a,0.,1.);}"
private const val FS = """precision highp float;
varying vec2 v;uniform sampler2D t;uniform vec2 sc,cn,sq,ofs;uniform vec3 p;
void main(){
vec2 u=(v-.5)*sc+.5;
vec2 d=(u-cn)*vec2(p.y,1.);
float w=1.-smoothstep(p.x*.65,p.x,length(d));
vec2 q=(u-cn)/(1.+w*sq)*(1.-w*p.z)-w*ofs;
gl_FragColor=texture2D(t,cn+q);}"""

private fun cs(type: Int, src: String): Int {
    val i = GLES20.glCreateShader(type); GLES20.glShaderSource(i, src); GLES20.glCompileShader(i); return i
}

object WiggleBus {
    val listeners = java.util.concurrent.CopyOnWriteArraySet<(Int, Float, Float) -> Unit>()
    fun send(a: Int, x: Float, y: Float) { for (l in listeners) l(a, x, y) }
}

object WiggleBridge {
    @Volatile var engine: WiggleService.E? = null
    @Volatile var svc: WiggleAccessService? = null
}

class WiggleService : WallpaperService() {
    override fun onCreateEngine(): Engine = E()

    inner class E : Engine(), SensorEventListener {
        private lateinit var gl: GV
        private val sm by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        private val grav = FloatArray(3); private var gotGrav = false
        private var lastShake = 0L; private var sensing = false
        private val rd = R()
        private val pos = FloatArray(4); private val vel = FloatArray(4); private val tgt = FloatArray(4)
        private var down = false; private var running = false; private var last = 0L
        private var w = 1; private var h = 1

        inner class GV(c: Context) : GLSurfaceView(c) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            fun destroy() { super.onDetachedFromWindow() }
        }

        override fun onCreate(sh: SurfaceHolder) {
            super.onCreate(sh)
            gl = GV(this@WiggleService).apply {
                setEGLContextClientVersion(2); setRenderer(rd); renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }
            setTouchEventsEnabled(true)
            WiggleBus.listeners.add(ext)
        }
        private val ext: (Int, Float, Float) -> Unit = { a, x, y -> handleTouch(a, x, y) }
        override fun onSurfaceCreated(sh: SurfaceHolder) { super.onSurfaceCreated(sh); gl.surfaceCreated(sh) }
        override fun onSurfaceChanged(sh: SurfaceHolder, f: Int, nw: Int, nh: Int) {
            super.onSurfaceChanged(sh, f, nw, nh); w = nw; h = nh; gl.surfaceChanged(sh, f, nw, nh)
        }
        override fun onSurfaceDestroyed(sh: SurfaceHolder) { super.onSurfaceDestroyed(sh); gl.surfaceDestroyed(sh) }
        override fun onVisibilityChanged(v: Boolean) {
            if (v) {
                rd.reload = true; gl.onResume(); gl.requestRender()
                startSensing()
                WiggleBridge.engine = this; handler.post(poll)
                // Lock screen e touch ashe na, tai screen on hole nije theke ekta wiggle
                val locked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
                if (locked) handler.postDelayed(wake, 350)
            } else {
                handler.removeCallbacks(wake); handler.removeCallbacks(poll); stopSensing(); gl.onPause()
                if (WiggleBridge.engine === this) WiggleBridge.engine = null
                WiggleBridge.svc?.refresh()
            }
        }
        override fun onDestroy() { WiggleBus.listeners.remove(ext); handler.removeCallbacks(wake); stopSensing(); super.onDestroy(); gl.destroy() }

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val poll = object : Runnable {
            override fun run() { WiggleBridge.svc?.refresh(); handler.postDelayed(this, 700) }
        }
        private val wake = Runnable { vel[0] += -2.5f; vel[1] += 2.5f; vel[2] += .08f; kick() }

        private fun startSensing() {
            if (sensing) return
            val a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            gotGrav = false
            sensing = sm.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME)
        }
        private fun stopSensing() { if (sensing) { sm.unregisterListener(this); sensing = false } }

        // Lock screen e touch na thakleo phone nara/tilt korle wiggle hobe
        override fun onSensorChanged(ev: SensorEvent) {
            val x = ev.values[0]; val y = ev.values[1]; val z = ev.values[2]
            if (!gotGrav) { grav[0] = x; grav[1] = y; grav[2] = z; gotGrav = true; return }
            grav[0] += .1f * (x - grav[0]); grav[1] += .1f * (y - grav[1]); grav[2] += .1f * (z - grav[2])
            val ax = x - grav[0]; val ay = y - grav[1]; val az = z - grav[2]
            val mag = sqrt(ax * ax + ay * ay + az * az)
            if (mag < 1.2f) return
            val now = System.currentTimeMillis()
            if (now - lastShake < 60) return
            lastShake = now
            val k = min(mag, 12f) / 12f
            vel[2] += -ax * .02f; vel[3] += ay * .02f
            vel[0] += -2.5f * k; vel[1] += 2.5f * k
            kick()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}

        override fun onTouchEvent(e: MotionEvent) { handleTouch(e.actionMasked, e.x, e.y) }

        // Lock screen overlay (Accessibility) theke asha touch (screen px)
        fun ext(e: MotionEvent) { handleTouch(e.actionMasked, e.rawX, e.rawY) }

        private fun handleTouch(a: Int, x: Float, y: Float) {
            when (a) {
                MotionEvent.ACTION_DOWN -> { down = true; poke(x, y, true) }
                MotionEvent.ACTION_MOVE -> poke(x, y, false)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    down = false; tgt[2] = 0f; tgt[3] = 0f; vel[0] += 1.5f; vel[1] += 1.5f; kick()
                }
            }
        }

        // Selected wiggle area ta screen e kothay (px), jeta overlay cover korbe
        fun region(): android.graphics.Rect? {
            if (rd.scx <= 0f || rd.scy <= 0f || w <= 1 || h <= 1) return null
            val cxp = ((rd.cx - .5f) / rd.scx + .5f) * w
            val cyp = ((rd.cy - .5f) / rd.scy + .5f) * h
            val rx = rd.rad / (rd.ia * rd.scx) * w * 1.6f
            val ry = rd.rad / rd.scy * h * 1.6f
            val r = android.graphics.Rect(
                (cxp - rx).toInt().coerceAtLeast(0), (cyp - ry).toInt().coerceAtLeast(0),
                (cxp + rx).toInt().coerceAtMost(w), (cyp + ry).toInt().coerceAtMost(h))
            return if (r.width() > 10 && r.height() > 10) r else null
        }
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP || action == WallpaperManager.COMMAND_SECONDARY_TAP)
                poke(x.toFloat(), y.toFloat(), true)
            return null
        }

        private fun poke(px: Float, py: Float, press: Boolean) {
            val ux = (px / w - .5f) * rd.scx + .5f
            val uy = (py / h - .5f) * rd.scy + .5f
            val dx = (ux - rd.cx) * rd.ia / rd.rad
            val dy = (uy - rd.cy) / rd.rad
            if (sqrt(dx * dx + dy * dy) > 1.6f) return
            if (press) {
                vel[2] -= dx * .3f; vel[3] -= dy * .3f
                vel[0] += -1f - abs(dx) + abs(dy); vel[1] += -1f + abs(dx) - abs(dy)
            } else { tgt[2] = -dx * .03f; tgt[3] = -dy * .03f }
            kick()
        }

        private val fc = object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                val dt = if (last == 0L) .016f else min((t - last) / 1e9f, .033f)
                last = t
                var e = 0f
                for (i in 0..3) {
                    vel[i] += (-180f * (pos[i] - tgt[i]) - 7f * vel[i]) * dt
                    pos[i] += vel[i] * dt
                    e += abs(pos[i] - tgt[i]) + abs(vel[i]) * .05f
                }
                val rest = !down && e < .002f
                if (rest) for (i in 0..3) { pos[i] = 0f; vel[i] = 0f }
                rd.s0 = pos[0].coerceIn(-.5f, .5f); rd.s1 = pos[1].coerceIn(-.5f, .5f)
                rd.o0 = pos[2]; rd.o1 = pos[3]
                gl.requestRender()
                if (rest) { running = false; last = 0L } else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        private fun kick() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(fc) } }

        inner class R : GLSurfaceView.Renderer {
            @Volatile var reload = true
            @Volatile var scx = 1f; @Volatile var scy = 1f
            @Volatile var cx = .5f; @Volatile var cy = .5f; @Volatile var rad = .15f; @Volatile var ia = 1f
            @Volatile var s0 = 0f; @Volatile var s1 = 0f; @Volatile var o0 = 0f; @Volatile var o1 = 0f
            private var prog = 0; private var tex = 0; private var sw = 1; private var sh = 1
            private val quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

            override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
                prog = GLES20.glCreateProgram()
                GLES20.glAttachShader(prog, cs(GLES20.GL_VERTEX_SHADER, VS))
                GLES20.glAttachShader(prog, cs(GLES20.GL_FRAGMENT_SHADER, FS))
                GLES20.glLinkProgram(prog); tex = 0; reload = true
            }
            override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) {
                GLES20.glViewport(0, 0, width, height); sw = width; sh = height; reload = true
            }
            private fun load() {
                val p = getSharedPreferences("w", 0)
                cx = p.getFloat("cx", .5f); cy = p.getFloat("cy", .5f); rad = p.getFloat("r", .15f)
                val f = File(filesDir, "bg.jpg"); if (!f.exists()) return
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
                val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
                if (tex == 0) { val a = IntArray(1); GLES20.glGenTextures(1, a, 0); tex = a[0] }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                ia = b.width.toFloat() / b.height; b.recycle()
                val sa = sw.toFloat() / sh
                if (sa > ia) { scx = 1f; scy = ia / sa } else { scx = sa / ia; scy = 1f }
            }
            override fun onDrawFrame(g: GL10?) {
                if (reload) { load(); reload = false }
                if (tex == 0) return
                GLES20.glUseProgram(prog)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                fun u(n: String) = GLES20.glGetUniformLocation(prog, n)
                GLES20.glUniform2f(u("sc"), scx, scy); GLES20.glUniform2f(u("cn"), cx, cy)
                GLES20.glUniform3f(u("p"), rad, ia, .12f)
                GLES20.glUniform2f(u("sq"), s0, s1); GLES20.glUniform2f(u("ofs"), o0, o1)
                val a = GLES20.glGetAttribLocation(prog, "a")
                GLES20.glEnableVertexAttribArray(a)
                GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        }
    }
}
