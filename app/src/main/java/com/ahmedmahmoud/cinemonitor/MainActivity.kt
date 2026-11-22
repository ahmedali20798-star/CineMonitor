package com.ahmedmahmoud.cinemonitor

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ahmedmahmoud.cinemonitor.camera.CanonPtpController
import com.ahmedmahmoud.cinemonitor.gl.CinemaRenderer
import com.ahmedmahmoud.cinemonitor.ui.CinemaMonitorScreen
import com.ahmedmahmoud.cinemonitor.usb.UvcVideoCaptureManager

class MainActivity : ComponentActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.ahmedmahmoud.cinemonitor.USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager
    private lateinit var ptpController: CanonPtpController
    private lateinit var uvcManager: UvcVideoCaptureManager
    private lateinit var cinemaRenderer: CinemaRenderer

    private var isCanonConnected by mutableStateOf(false)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device?.vendorId == CanonPtpController.CANON_VENDOR_ID) {
                        connectCanonDevice(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device?.vendorId == CanonPtpController.CANON_VENDOR_ID) {
                        requestOrConnectCanon(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device?.vendorId == CanonPtpController.CANON_VENDOR_ID) {
                        ptpController.disconnect()
                        isCanonConnected = false
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        ptpController = CanonPtpController(usbManager)
        uvcManager = UvcVideoCaptureManager(this)
        cinemaRenderer = CinemaRenderer { surfaceTexture ->
            uvcManager.attachSurfaceTexture(surfaceTexture)
        }

        registerUsbReceiver()
        scanConnectedDevices()

        setContent {
            CinemaMonitorScreen(
                ptpController = ptpController,
                renderer = cinemaRenderer,
                isCameraConnected = isCanonConnected
            )
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun scanConnectedDevices() {
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == CanonPtpController.CANON_VENDOR_ID
        }?.let { canonDevice ->
            requestOrConnectCanon(canonDevice)
        }
    }

    private fun requestOrConnectCanon(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectCanonDevice(device)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectCanonDevice(device: UsbDevice) {
        if (ptpController.connect(device)) {
            isCanonConnected = true
            ptpController.startEventPolling(lifecycleScope)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        ptpController.disconnect()
        uvcManager.release()
    }
}
