package com.raycenter.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color

class MainActivity : Activity() {
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var details: TextView

    private val permissionAction = "com.raycenter.app.USB_PERMISSION"

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                status.text = if (granted && device != null) {
                    "USB-разрешение получено. Камера обнаружена."
                } else {
                    "Доступ к USB-устройству не разрешён."
                }
                showDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()

        val filter = IntentFilter(permissionAction)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(permissionReceiver, filter)
        }
        showDevices()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 26, 22, 22)
            setBackgroundColor(Color.rgb(16, 24, 32))
        }
        root.addView(TextView(this).apply {
            text = "RayCenter"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Центровка лазерного луча • BM111"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 24)
        })
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.rgb(0, 220, 120))
            text = "Проверка USB..."
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Найти USB-камеру"
            setOnClickListener { showDevices() }
        })
        details = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 18)
        }
        root.addView(details)
        root.addView(TextView(this).apply {
            text = "Этап 1/3: обнаружение камеры. Эта сборка проверяет USB-подключение и разрешение Android. Видеопоток и измерение центровки пока не реализованы."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, 20, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showDevices() {
        try {
            val devices = usbManager.deviceList.values.toList()
            if (devices.isEmpty()) {
                status.text = "USB-камера не обнаружена"
                details.text = "Подключи камеру через OTG-переходник и нажми «Найти USB-камеру»."
                return
            }

            status.text = "Найдено USB-устройств: ${devices.size}"
            details.text = devices.joinToString("\n\n") { d ->
                "Устройство: ${d.deviceName}\nVID: 0x${d.vendorId.toString(16)}  PID: 0x${d.productId.toString(16)}\nИнтерфейсы: ${d.interfaceCount}\nКлассы: " +
                    (0 until d.interfaceCount).joinToString { i -> d.getInterface(i).interfaceClass.toString() }
            }

            val camera = devices.firstOrNull { d ->
                (0 until d.interfaceCount).any { i ->
                    d.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO
                }
            }

            if (camera != null) {
                status.text = "Обнаружено USB-видеоустройство"
                if (!usbManager.hasPermission(camera)) {
                    requestUsbPermission(camera)
                } else {
                    status.text = "USB-доступ уже разрешён. Камера готова к следующему этапу интеграции."
                }
            } else {
                status.text = "USB-устройства найдены, но UVC-интерфейс не определён"
            }
        } catch (e: Exception) {
            status.text = "Ошибка проверки USB: ${e.javaClass.simpleName}"
            details.text = e.localizedMessage ?: "Не удалось прочитать список USB-устройств."
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        try {
            // Explicit package is required for mutable PendingIntents on recent Android versions.
            val permissionIntent = Intent(permissionAction).setPackage(packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                permissionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            usbManager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            status.text = "Не удалось запросить USB-доступ: ${e.javaClass.simpleName}"
            details.text = e.localizedMessage ?: "Проверь OTG-переходник и повтори подключение."
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(permissionReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
