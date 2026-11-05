package com.raycenter.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.mikimn.droiduvc.AutoExposureMode
import com.mikimn.droiduvc.ControlId
import com.mikimn.droiduvc.StreamFormat
import com.mikimn.droiduvc.UvcCamera
import com.mikimn.droiduvc.uvcDevices
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.sqrt

class MainActivity : Activity() {
    private enum class Stage { LIVE, NOZZLE, LASER }
    private data class PointN(val x: Float, val y: Float)

    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var measurement: TextView
    private lateinit var preview: TextureView
    private lateinit var guide: AlignmentOverlay
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var connection: UsbDeviceConnection? = null
    private var camera: UvcCamera? = null
    private var surface: Surface? = null
    @Volatile private var streaming = false
    private var wantsStream = false
    private var selectedMode: com.mikimn.droiduvc.StreamMode? = null
    private var monochromeEnabled = true
    private var stage = Stage.LIVE
    private var nozzleCenter: PointN? = null
    private var laserCenter: PointN? = null
    private var analysisBusy = false
    private val usbPermissionAction = "com.raycenter.app.USB_PERMISSION"

    private val analysisLoop = object : Runnable {
        override fun run() {
            if (streaming && preview.isAvailable && !analysisBusy && stage != Stage.LIVE) {
                val frame = try { preview.bitmap } catch (_: Exception) { null }
                if (frame != null) {
                    analysisBusy = true
                    worker.execute {
                        val small = Bitmap.createScaledBitmap(frame, 320, 180, true)
                        if (small !== frame) frame.recycle()
                        val found = if (stage == Stage.NOZZLE) FrameAnalyzer.findDarkOpening(small) else {
                            val anchor = nozzleCenter
                            if (anchor == null) null else FrameAnalyzer.findBrightSpot(small, anchor)
                        }
                        small.recycle()
                        runOnUiThread {
                            analysisBusy = false
                            if (found != null) {
                                if (stage == Stage.NOZZLE) {
                                    nozzleCenter = found
                                    guide.nozzle = found
                                    measurement.text = "Центр сопла найден • X " + px(found.x) + " • Y " + px(found.y)
                                } else {
                                    laserCenter = found
                                    guide.laser = found
                                    updateMeasurement()
                                }
                            } else {
                                measurement.text = if (stage == Stage.NOZZLE)
                                    "Ищу тёмное отверстие сопла… Проверь подсветку и положение камеры"
                                else "Не вижу пятно луча. Проверь экспозицию и включение пилотного лазера"
                            }
                            guide.invalidate()
                        }
                    }
                }
            }
            ui.postDelayed(this, 300)
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != usbPermissionAction) return
            val device = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                openCamera(device)
            } else status.text = "Доступ к USB-камере не разрешён."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val filter = IntentFilter(usbPermissionAction)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(usbPermissionReceiver, filter)
        }
        ui.post(analysisLoop)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(12, 18, 25))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "RayCenter"
            textSize = 22f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply {
            text = "Подключи USB-камеру и нажми «Запустить»"
            textSize = 12f
            setTextColor(Color.rgb(80, 220, 150))
        }
        top.addView(status)
        root.addView(top)

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val videoFrame = FrameLayout(this)
        preview = TextureView(this)
        applyMonochrome()
        guide = AlignmentOverlay(this)
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                if (wantsStream && camera != null) startPreview()
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                stopPreview()
                return true
            }
        }
        videoFrame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        videoFrame.addView(guide, FrameLayout.LayoutParams(-1, -1))
        frame.addView(videoFrame, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val fw = frame.width
            val fh = frame.height
            if (fw > 0 && fh > 0) {
                var vw = fw
                var vh = (vw * 9f / 16f).toInt()
                if (vh > fh) {
                    vh = fh
                    vw = (vh * 16f / 9f).toInt()
                }
                videoFrame.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
            }
        }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 6; bottomMargin = 5 })

        measurement = TextView(this).apply {
            text = "1. Включи кольцевую подсветку и нажми «Найти сопло»"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(2, 4, 2, 4)
        }
        root.addView(measurement)

        val stages = LinearLayout(this).apply { gravity = Gravity.CENTER }
        stages.addView(Button(this).apply {
            text = "Найти сопло"
            setOnClickListener {
                if (!streaming) { status.text = "Сначала запусти видеопоток"; return@setOnClickListener }
                stage = Stage.NOZZLE
                nozzleCenter = null
                laserCenter = null
                guide.nozzle = null
                guide.laser = null
                guide.invalidate()
                measurement.text = "Кольцевая подсветка ВКЛ • пилотный луч ВЫКЛ"
                status.text = "Поиск тёмного центра сопла…"
            }
        })
        stages.addView(Button(this).apply {
            text = "Найти луч"
            setOnClickListener {
                if (nozzleCenter == null) {
                    status.text = "Сначала выполни поиск центра сопла"
                } else {
                    stage = Stage.LASER
                    laserCenter = null
                    guide.laser = null
                    guide.invalidate()
                    measurement.text = "Кольцевая подсветка ВЫКЛ • включи пилотный луч"
                    status.text = "Поиск светового пятна…"
                }
            }
        })
        stages.addView(Button(this).apply {
            text = "Сброс"
            setOnClickListener {
                stage = Stage.LIVE
                nozzleCenter = null
                laserCenter = null
                guide.nozzle = null
                guide.laser = null
                guide.invalidate()
                measurement.text = "Центры сброшены. Начни с поиска сопла."
                status.text = "Ожидание"
            }
        })
        root.addView(stages)

        val cameraButtons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        cameraButtons.addView(Button(this).apply { text = "Старт"; setOnClickListener { requestStart() } })
        cameraButtons.addView(Button(this).apply { text = "Стоп"; setOnClickListener { wantsStream = false; closeCamera() } })
        cameraButtons.addView(Button(this).apply { text = "Фото"; setOnClickListener { savePhoto() } })
        root.addView(cameraButtons)

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        controls.addView(Button(this).apply { text = "Авто эксп."; setOnClickListener { toggleAutoExposure() } })
        controls.addView(Button(this).apply { text = "Эксп. −"; setOnClickListener { adjustControl(ControlId.EXPOSURE_TIME, -1) } })
        controls.addView(Button(this).apply { text = "Эксп. +"; setOnClickListener { adjustControl(ControlId.EXPOSURE_TIME, 1) } })
        controls.addView(Button(this).apply { text = "Gain −"; setOnClickListener { adjustControl(ControlId.GAIN, -1) } })
        controls.addView(Button(this).apply { text = "Gain +"; setOnClickListener { adjustControl(ControlId.GAIN, 1) } })
        controls.addView(Button(this).apply {
            text = "Моно: ВКЛ"
            setOnClickListener {
                monochromeEnabled = !monochromeEnabled
                applyMonochrome()
                text = if (monochromeEnabled) "Моно: ВКЛ" else "Моно: ВЫКЛ"
            }
        })
        root.addView(controls)
        root.addView(TextView(this).apply {
            text = "Подсветку переключай вручную. Зелёный крест — центр сопла, красная точка — пилотный луч."
            textSize = 11f
            setTextColor(Color.LTGRAY)
        })
        setContentView(root)
    }

    private fun px(v: Float): String = String.format(Locale.US, "%.0f%%", (v - 0.5f) * 100f)

    private fun updateMeasurement() {
        val a = nozzleCenter
        val b = laserCenter
        if (a == null || b == null) return
        val dx = (b.x - a.x) * 320f
        val dy = (b.y - a.y) * 180f
        val distance = sqrt(dx * dx + dy * dy)
        measurement.text = String.format(Locale.US, "Отклонение: X %+.1f px • Y %+.1f px • всего %.1f px", dx, dy, distance)
    }

    private fun requestStart() {
        wantsStream = true
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
            return
        }
        val device = usb.uvcDevices().firstOrNull()
        if (device == null) { status.text = "UVC-камера не найдена. Проверь OTG."; return }
        if (usb.hasPermission(device)) openCamera(device) else {
            status.text = "Ожидаю разрешение Android на USB..."
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(usbPermissionAction).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(device, pi)
        }
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100) {
            if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) requestStart()
            else status.text = "Разреши доступ к камере в Android."
        }
    }

    private fun openCamera(device: UsbDevice) {
        status.text = "Открываю UVC-камеру..."
        worker.execute {
            try {
                releaseCameraInternal()
                val conn = usb.openDevice(device) ?: error("USB openDevice вернул null")
                val opened = UvcCamera.open(conn.fileDescriptor)
                val modes = opened.supportedModes().filter { it.format == StreamFormat.MJPEG || it.format == StreamFormat.YUYV }
                if (modes.isEmpty()) error("Камера не сообщила поддерживаемые видеорежимы")
                val widescreen = modes.filter { kotlin.math.abs(it.width.toFloat() / it.height - 16f / 9f) < 0.025f }
                selectedMode = (widescreen.ifEmpty { modes }).maxWithOrNull(
                    compareBy<com.mikimn.droiduvc.StreamMode> { it.fps >= 25 }
                        .thenBy { it.fps }
                        .thenBy { if (it.format == StreamFormat.MJPEG) 1 else 0 }
                        .thenBy { it.width.toLong() * it.height })
                connection = conn
                camera = opened
                runOnUiThread {
                    status.text = "Камера готова • " + selectedMode?.width + "×" + selectedMode?.height
                    if (wantsStream) startPreview()
                }
            } catch (e: Exception) {
                releaseCameraInternal()
                runOnUiThread { status.text = "Ошибка открытия: " + (e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun startPreview() {
        if (!preview.isAvailable || streaming || !wantsStream) return
        val st = preview.surfaceTexture ?: return
        val cam = camera ?: return
        val modeForBuffer = selectedMode
        if (modeForBuffer != null) st.setDefaultBufferSize(modeForBuffer.width, modeForBuffer.height)
        val target = Surface(st)
        surface = target
        status.text = "Запускаю видео..."
        worker.execute {
            try {
                val mode = selectedMode
                val actual = if (mode != null) cam.start(target, mode) else cam.start(target, 640, 480, 30, StreamFormat.MJPEG)
                streaming = true
                runOnUiThread {
                    preview.surfaceTexture?.setDefaultBufferSize(actual.width, actual.height)
                    status.text = "ВИДЕО • " + actual.width + "×" + actual.height + " • " + actual.fps + " fps"
                }
            } catch (e: Exception) {
                streaming = false
                target.release()
                surface = null
                runOnUiThread { status.text = "Ошибка видеопотока: " + (e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun applyMonochrome() {
        val paint = Paint().apply { color = Color.WHITE }
        if (monochromeEnabled) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        preview.setLayerPaint(paint)
    }

    private fun toggleAutoExposure() {
        worker.execute {
            try {
                val control = camera?.controls?.autoExposureMode ?: error("Камера не поддерживает автоэкспозицию")
                val options = control.options.orEmpty()
                val current = control.value
                val next = if (current == AutoExposureMode.AUTO && AutoExposureMode.MANUAL in options) AutoExposureMode.MANUAL else AutoExposureMode.AUTO
                if (next !in options) error("Режим не поддерживается")
                control.set(next)
                runOnUiThread { status.text = if (next == AutoExposureMode.AUTO) "Автоэкспозиция ВКЛ" else "Ручная экспозиция ВКЛ" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Экспозиция: " + (e.message ?: "недоступно") }
            }
        }
    }

    private fun adjustControl(id: ControlId, direction: Int) {
        worker.execute {
            try {
                val cam = camera ?: error("Сначала подключи камеру")
                if (id == ControlId.EXPOSURE_TIME || id == ControlId.GAIN) {
                    val auto = cam.controls.autoExposureMode
                    if (auto != null && AutoExposureMode.MANUAL in auto.options.orEmpty() && auto.value != AutoExposureMode.MANUAL) auto.set(AutoExposureMode.MANUAL)
                }
                val control = cam.controls[id] ?: error("Камера не поддерживает " + id.name)
                val range = control.range ?: error("Нет диапазона " + id.name)
                val step = range.step.coerceAtLeast(1)
                val next = (control.value.toLong() + direction.toLong() * step).coerceIn(range.min.toLong(), range.max.toLong()).toInt()
                control.set(next)
                val applied = control.value
                runOnUiThread { status.text = id.name + ": " + applied + " (" + range.min + "–" + range.max + ")" }
            } catch (e: Exception) {
                runOnUiThread { status.text = id.name + ": " + (e.message ?: "недоступно") }
            }
        }
    }

    private fun stopPreview() {
        val cam = camera
        val oldSurface = surface
        surface = null
        streaming = false
        worker.execute {
            try { cam?.stop() } catch (_: Exception) {}
            try { oldSurface?.release() } catch (_: Exception) {}
        }
    }

    private fun closeCamera() {
        status.text = "Останавливаю..."
        worker.execute {
            releaseCameraInternal()
            runOnUiThread { status.text = "Камера остановлена" }
        }
    }

    private fun releaseCameraInternal() {
        try { camera?.stop() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        camera = null
        try { surface?.release() } catch (_: Exception) {}
        surface = null
        streaming = false
        try { connection?.close() } catch (_: Exception) {}
        connection = null
    }

    private fun savePhoto() {
        val bitmap = preview.bitmap ?: run {
            Toast.makeText(this, "Сначала запусти видео", Toast.LENGTH_SHORT).show()
            return
        }
        worker.execute {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "RayCenter_" + stamp + ".jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RayCenter")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать файл")
                contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) } ?: error("Не удалось записать файл")
                runOnUiThread { Toast.makeText(this, "Фото сохранено: Pictures/RayCenter", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка фото: " + (e.message ?: "неизвестно"), Toast.LENGTH_LONG).show() }
            } finally { bitmap.recycle() }
        }
    }

    override fun onStop() {
        wantsStream = false
        closeCamera()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(analysisLoop)
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        worker.shutdown()
        super.onDestroy()
    }

    private class AlignmentOverlay(context: Context) : View(context) {
        var nozzle: PointN? = null
        var laser: PointN? = null
        private val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 235, 120); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; strokeWidth = 2f }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val a = nozzle
            val b = laser
            if (a != null) drawTarget(c, a, green)
            if (a != null && b != null) c.drawLine(a.x * width, a.y * height, b.x * width, b.y * height, line)
            if (b != null) drawTarget(c, b, red)
        }
        private fun drawTarget(c: Canvas, p: PointN, paint: Paint) {
            val x = p.x * width
            val y = p.y * height
            val r = 13f
            c.drawCircle(x, y, r, paint)
            c.drawLine(x - 22, y, x + 22, y, paint)
            c.drawLine(x, y - 22, x, y + 22, paint)
        }
    }

    private object FrameAnalyzer {
        private data class Component(val x: Float, val y: Float, val area: Int, val score: Float)

        fun findDarkOpening(bitmap: Bitmap): PointN? = findComponent(bitmap, false, null)
        fun findBrightSpot(bitmap: Bitmap, anchor: PointN): PointN? = findComponent(bitmap, true, anchor)

        private fun findComponent(bitmap: Bitmap, bright: Boolean, anchor: PointN?): PointN? {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val seen = BooleanArray(w * h)
            val queue = IntArray(w * h)
            val candidates = ArrayList<Component>()
            val minX = (w * 0.12f).toInt()
            val maxX = (w * 0.88f).toInt()
            val minY = (h * 0.12f).toInt()
            val maxY = (h * 0.88f).toInt()
            val threshold = if (bright) 220 else 62
            val minArea = if (bright) 3 else (w * h * 0.002f).toInt().coerceAtLeast(12)
            val maxArea = (w * h * 0.28f).toInt()

            fun matches(index: Int): Boolean {
                val color = pixels[index]
                val lum = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                return if (bright) lum >= threshold else lum <= threshold
            }

            for (sy in minY until maxY) {
                for (sx in minX until maxX) {
                    val start = sy * w + sx
                    if (seen[start] || !matches(start)) continue
                    var head = 0
                    var tail = 0
                    queue[tail++] = start
                    seen[start] = true
                    var sumX = 0L
                    var sumY = 0L
                    var area = 0
                    while (head < tail) {
                        val idx = queue[head++]
                        val x = idx % w
                        val y = idx / w
                        sumX += x
                        sumY += y
                        area++
                        val neighbors = intArrayOf(idx - 1, idx + 1, idx - w, idx + w)
                        for (n in neighbors) {
                            if (n < 0 || n >= w * h || seen[n]) continue
                            val nx = n % w
                            val ny = n / w
                            if (nx < minX || nx >= maxX || ny < minY || ny >= maxY) continue
                            if (matches(n)) {
                                seen[n] = true
                                queue[tail++] = n
                            }
                        }
                    }
                    if (area < minArea || area > maxArea) continue
                    val cx = sumX.toFloat() / area / w
                    val cy = sumY.toFloat() / area / h
                    val dist = if (anchor == null) hypot(cx - 0.5f, cy - 0.5f) else hypot((cx - anchor.x) * w, (cy - anchor.y) * h) / w
                    val score = if (bright) area.toFloat() / (1f + dist * 18f) else area.toFloat() / (1f + dist * 5f)
                    candidates.add(Component(cx, cy, area, score))
                }
            }
            val best = candidates.maxByOrNull { it.score } ?: return null
            return PointN(best.x, best.y)
        }
    }
}
