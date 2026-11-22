package com.ahmedmahmoud.cinemonitor.usb

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import com.ahmedmahmoud.cinemonitor.camera.CanonPtpController
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper

class UvcVideoCaptureManager(context: Context) {

    private val cameraHelper: ICameraHelper = CameraHelper()
    private var targetSurfaceTexture: SurfaceTexture? = null
    private var isCameraOpened = false

    init {
        cameraHelper.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (device.vendorId == CanonPtpController.CANON_VENDOR_ID) return
                cameraHelper.selectDevice(device)
            }

            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                cameraHelper.openCamera()
            }

            override fun onCameraOpen(device: UsbDevice) {
                isCameraOpened = true
                cameraHelper.previewSize = cameraHelper.supportedSizeList?.firstOrNull {
                    it.width == 1920 && it.height == 1080
                } ?: cameraHelper.previewSize

                targetSurfaceTexture?.let { st ->
                    cameraHelper.addSurface(st, false)
                }
                cameraHelper.startPreview()
            }

            override fun onCameraClose(device: UsbDevice) {
                isCameraOpened = false
            }

            override fun onDeviceClose(device: UsbDevice) {}
            override fun onDetach(device: UsbDevice) {}
            override fun onCancel(device: UsbDevice) {}
        })
    }

    fun attachSurfaceTexture(surfaceTexture: SurfaceTexture) {
        targetSurfaceTexture = surfaceTexture
        if (isCameraOpened) {
            cameraHelper.addSurface(surfaceTexture, false)
            cameraHelper.startPreview()
        }
    }

    fun release() {
        targetSurfaceTexture?.let { cameraHelper.removeSurface(it) }
        cameraHelper.release()
    }
}
