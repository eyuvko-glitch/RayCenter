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
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Paint
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var preview: TextureView
    private val worker = Executors.newSingleThreadExecutor()
    private var connection: UsbDeviceConnection? = null
    private var camera: UvcCamera? = null
    private var surface: Surface? = null
    @Volatile private var streaming = false
    private var wantsStream = false
    private var selectedMode: com.mikimn.droiduvc.StreamMode? = null
    private val usbPermissionAction = "com.raycenter.app.USB_PERMISSION"

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
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            setBackgroundColor(Color.rgb(12, 18, 25))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "RayCenter"
            textSize = 24f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        status = TextView(this).apply {
            text = "Подключи USB-камеру и нажми «Запустить»"
            textSize = 13f
            setTextColor(Color.rgb(80, 220, 150))
        }
        top.addView(status)
        root.addView(top)

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = TextureView(this)
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
        frame.addView(preview, FrameLayout.LayoutParams(-1, -1))
        frame.addView(CenterGuide(this), FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 8; bottomMargin = 8 })

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        buttons.addView(Button(this).apply { text = "Запустить"; setOnClickListener { requestStart() } })
        buttons.addView(Button(this).apply { text = "Стоп"; setOnClickListener { wantsStream = false; closeCamera() } })
        buttons.addView(Button(this).apply { text = "Фото"; setOnClickListener { savePhoto() } })
        root.addView(buttons)
        val cameraControls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        cameraControls.addView(Button(this).apply { text = "Авто эксп."; setOnClickListener { toggleAutoExposure() } })
        cameraControls.addView(Button(this).apply { text = "Эксп. −"; setOnClickListener { adjustControl(ControlId.EXPOSURE_TIME, -1) } })
        cameraControls.addView(Button(this).apply { text = "Эксп. +"; setOnClickListener { adjustControl(ControlId.EXPOSURE_TIME, 1) } })
        cameraControls.addView(Button(this).apply { text = "Gain −"; setOnClickListener { adjustControl(ControlId.GAIN, -1) } })
        cameraControls.addView(Button(this).apply { text = "Gain +"; setOnClickListener { adjustControl(ControlId.GAIN, 1) } })
        root.addView(cameraControls)
        root.addView(TextView(this).apply {
            text = "Сейчас: видеопоток и прицельная сетка. Автопоиск центра сопла и лазерной точки добавим после проверки изображения."
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, 6, 0, 0)
        })
        setContentView(root)
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
                    compareBy<com.mikimn.droiduvc.StreamMode> { it.width.toLong() * it.height }
                        .thenBy { if (it.format == StreamFormat.MJPEG) 1 else 0 })
                connection = conn
                camera = opened
                runOnUiThread {
                    status.text = "Камера готова • ${selectedMode?.width}×${selectedMode?.height} • режимов: ${modes.size}"
                    if (wantsStream) startPreview()
                }
            } catch (e: Exception) {
                releaseCameraInternal()
                runOnUiThread { status.text = "Ошибка открытия: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun startPreview() {
        if (!preview.isAvailable || streaming || !wantsStream) return
        val st = preview.surfaceTexture ?: return
        val cam = camera ?: return
        val target = Surface(st)
        surface = target
        status.text = "Запускаю видео..."
        worker.execute {
            try {
                val mode = selectedMode
                val actual = if (mode != null) cam.start(target, mode) else cam.start(target, 640, 480, 30, StreamFormat.MJPEG)
                streaming = true
                runOnUiThread {
                    applyPreviewTransform(actual.width, actual.height)
                    status.text = "ВИДЕО • ${actual.width}×${actual.height} • ${actual.fps} fps • ${actual.format}"
                }
            } catch (e: Exception) {
                streaming = false
                target.release()
                surface = null
                runOnUiThread { status.text = "Ошибка видеопотока: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun applyPreviewTransform(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || preview.width <= 0 || preview.height <= 0) return
        val source = RectF(0f, 0f, videoWidth.toFloat(), videoHeight.toFloat())
        val destination = RectF(0f, 0f, preview.width.toFloat(), preview.height.toFloat())
        val matrix = Matrix()
        matrix.setRectToRect(source, destination, Matrix.ScaleToFit.CENTER)
        preview.setTransform(matrix)
    }

    private fun toggleAutoExposure() {
        worker.execute {
            try {
                val control = camera?.controls?.autoExposureMode ?: error("Камера не поддерживает переключение автоэкспозиции")
                val options = control.options.orEmpty()
                val current = control.value
                val next = if (current == AutoExposureMode.AUTO && AutoExposureMode.MANUAL in options) AutoExposureMode.MANUAL else AutoExposureMode.AUTO
                if (next !in options) error("Этот режим экспозиции камера не поддерживает")
                control.set(next)
                runOnUiThread { status.text = if (next == AutoExposureMode.AUTO) "Автоэкспозиция включена" else "Ручная экспозиция включена" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Экспозиция: ${e.message ?: "недоступно"}" }
            }
        }
    }

    private fun adjustControl(id: ControlId, direction: Int) {
        worker.execute {
            try {
                val control = camera?.controls?.get(id) ?: error("Камера не поддерживает ${id.name}")
                val range = control.range ?: error("Для ${id.name} нет диапазона")
                val current = control.value
                val step = range.step.coerceAtLeast(1)
                val next = (current.toLong() + direction.toLong() * step).coerceIn(range.min.toLong(), range.max.toLong()).toInt()
                control.set(next)
                runOnUiThread { status.text = "${id.name}: $next (диапазон ${range.min}–${range.max})" }
            } catch (e: Exception) {
                runOnUiThread { status.text = "${id.name}: ${e.message ?: "недоступно"}" }
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
                    put(MediaStore.Images.Media.DISPLAY_NAME, "RayCenter_$stamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RayCenter")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Не удалось создать файл")
                contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                    ?: error("Не удалось записать файл")
                runOnUiThread { Toast.makeText(this, "Фото сохранено: Pictures/RayCenter", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Ошибка фото: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally { bitmap.recycle() }
        }
    }

    override fun onStop() {
        wantsStream = false
        closeCamera()
        super.onStop()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
        worker.shutdown()
        super.onDestroy()
    }

    private class CenterGuide(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val x = width / 2f
            val y = height / 2f
            val r = minOf(width, height) * 0.23f
            c.drawCircle(x, y, r, p)
            c.drawLine(x - r * 1.35f, y, x + r * 1.35f, y, p)
            c.drawLine(x, y - r * 1.35f, x, y + r * 1.35f, p)
            p.style = Paint.Style.FILL
            c.drawCircle(x, y, 4f, p)
            p.style = Paint.Style.STROKE
        }
    }
}