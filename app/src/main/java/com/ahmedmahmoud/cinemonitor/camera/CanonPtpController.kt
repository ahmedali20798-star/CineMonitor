package com.ahmedmahmoud.cinemonitor.camera

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CanonPtpController(private val usbManager: UsbManager) {

    companion object {
        const val CANON_VENDOR_ID = 0x04A9

        const val PTP_OC_OPEN_SESSION: Short = 0x1002
        const val PTP_OC_CANON_SET_DEVICE_PROP_EX: Short = 0x9110.toShort()
        const val PTP_OC_CANON_SET_REMOTE_MODE: Short = 0x9114.toShort()
        const val PTP_OC_CANON_SET_EVENT_MODE: Short = 0x9115.toShort()
        const val PTP_OC_CANON_GET_EVENT_DATA: Short = 0x9116.toShort()
        const val PTP_OC_CANON_REMOTE_RELEASE_ON: Short = 0x9128.toShort()
        const val PTP_OC_CANON_REMOTE_RELEASE_OFF: Short = 0x9129.toShort()
        const val PTP_OC_CANON_MOVIE_RECORD: Short = 0x9133.toShort()
        const val PTP_OC_CANON_SET_LV_MODE: Short = 0x9153.toShort()
        const val PTP_OC_CANON_SET_LV_ZOOM_POS: Short = 0x9158.toShort()
        const val PTP_OC_CANON_DO_AF: Short = 0x9160.toShort()
        const val PTP_OC_CANON_AF_CANCEL: Short = 0x9161.toShort()

        const val PROP_APERTURE = 0xD101
        const val PROP_SHUTTER_SPEED = 0xD102
        const val PROP_ISO = 0xD103
        const val PROP_WHITE_BALANCE = 0xD109
        const val PROP_RECORDING_STATE = 0xD1B8
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var epOut: UsbEndpoint? = null
    private var epIn: UsbEndpoint? = null
    private var transactionId = 1
    private var pollingJob: Job? = null
    private val usbLock = Any()

    private val _cameraState = MutableStateFlow(CameraLiveState())
    val cameraState: StateFlow<CameraLiveState> = _cameraState

    fun connect(device: UsbDevice): Boolean {
        if (device.vendorId != CANON_VENDOR_ID) return false

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) {
                usbInterface = intf
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                        if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    }
                }
                break
            }
        }

        val conn = usbManager.openDevice(device) ?: return false
        val intf = usbInterface ?: return false
        if (!conn.claimInterface(intf, true)) return false

        connection = conn
        initializeRemoteSession()
        return true
    }

    private fun initializeRemoteSession() {
        sendCommand(PTP_OC_OPEN_SESSION, intArrayOf(1))
        sendCommand(PTP_OC_CANON_SET_REMOTE_MODE, intArrayOf(1))
        sendCommand(PTP_OC_CANON_SET_EVENT_MODE, intArrayOf(1))
    }

    fun setCameraProperty(propCode: Int, canonValueCode: Int) {
        synchronized(usbLock) {
            val txId = transactionId++
            val cmdPacket = buildPtpPacket(type = 1, code = PTP_OC_CANON_SET_DEVICE_PROP_EX, txId = txId)
            connection?.bulkTransfer(epOut, cmdPacket, cmdPacket.size, 1000)

            val payload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(12)
                putInt(propCode)
                putInt(canonValueCode)
            }.array()

            val dataPacket = buildPtpPacket(type = 2, code = PTP_OC_CANON_SET_DEVICE_PROP_EX, txId = txId, payload = payload)
            connection?.bulkTransfer(epOut, dataPacket, dataPacket.size, 1000)
            readSimpleResponse()
        }
    }

    fun toggleVideoRecording(start: Boolean) {
        val mode = if (start) 4 else 0
        setCameraProperty(PROP_RECORDING_STATE, mode)
    }

    fun triggerTouchAf(canonX: Int, canonY: Int, scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            sendCommand(PTP_OC_CANON_SET_LV_ZOOM_POS, intArrayOf(canonX, canonY))
            sendCommand(PTP_OC_CANON_DO_AF, intArrayOf())
            sendCommand(PTP_OC_CANON_REMOTE_RELEASE_ON, intArrayOf(1, 0))
            delay(150)
            sendCommand(PTP_OC_CANON_REMOTE_RELEASE_OFF, intArrayOf(1, 0))
        }
    }

    fun startEventPolling(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val eventData = synchronized(usbLock) {
                    val txId = transactionId++
                    val cmdPacket = buildPtpPacket(
                        type = 1,
                        code = PTP_OC_CANON_GET_EVENT_DATA,
                        txId = txId
                    )
                    connection?.bulkTransfer(epOut, cmdPacket, cmdPacket.size, 1000)
                    readFullDataResponse()
                }
                if (eventData != null) {
                    _cameraState.value = CanonEventParser.parseEventPacket(
                        rawBytes = eventData,
                        currentState = _cameraState.value
                    )
                }
                delay(200)
            }
        }
    }

    private fun sendCommand(opcode: Short, params: IntArray) {
        synchronized(usbLock) {
            val txId = transactionId++
            val payload = ByteBuffer.allocate(params.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                params.forEach { putInt(it) }
            }.array()
            val packet = buildPtpPacket(type = 1, code = opcode, txId = txId, payload = payload)
            connection?.bulkTransfer(epOut, packet, packet.size, 1000)
            readSimpleResponse()
        }
    }

    private fun buildPtpPacket(type: Short, code: Short, txId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val totalLength = 12 + payload.size
        return ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(totalLength)
            putShort(type)
            putShort(code)
            putInt(txId)
            put(payload)
        }.array()
    }

    private fun readSimpleResponse(): ByteArray? {
        val buffer = ByteArray(4096)
        val bytesRead = connection?.bulkTransfer(epIn, buffer, buffer.size, 1000) ?: -1
        return if (bytesRead > 0) buffer.copyOf(bytesRead) else null
    }

    private fun readFullDataResponse(): ByteArray? {
        val firstChunk = ByteArray(16384)
        val firstRead = connection?.bulkTransfer(epIn, firstChunk, firstChunk.size, 1000) ?: -1
        if (firstRead < 12) return null

        val totalDataSize = ByteBuffer.wrap(firstChunk, 0, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int

        if (totalDataSize <= 12 || totalDataSize > 512_000) return null

        val fullBuffer = ByteArray(totalDataSize)
        var copied = minOf(firstRead, totalDataSize)
        System.arraycopy(firstChunk, 0, fullBuffer, 0, copied)

        while (copied < totalDataSize) {
            val temp = ByteArray(16384)
            val n = connection?.bulkTransfer(epIn, temp, temp.size, 1000) ?: -1
            if (n <= 0) break
            val toCopy = minOf(n, totalDataSize - copied)
            System.arraycopy(temp, 0, fullBuffer, copied, toCopy)
            copied += toCopy
        }

        if (firstRead <= totalDataSize) {
            val ackBuffer = ByteArray(64)
            connection?.bulkTransfer(epIn, ackBuffer, ackBuffer.size, 500)
        }

        return fullBuffer
    }

    fun disconnect() {
        pollingJob?.cancel()
        synchronized(usbLock) {
            usbInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
            connection = null
        }
    }
}
