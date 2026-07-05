package com.nativegamepad

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class BluetoothHidSender(private val context: Context) {
    lateinit var isRumbleEnabled: Any
    private var hidDevice: BluetoothHidDevice? = null
    var connectedDevice: BluetoothDevice? = null
        private set
    private var isRegistered = false
    private var isRunning = false
    private var thread: Thread? = null

    val state = ControllerState()
    private val lastSentState = ControllerState()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                registerApp()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
            }
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isRegistered = registered
            if (registered) {
                if (pluggedDevice != null) {
                    connectedDevice = pluggedDevice
                    showToast("Bluetooth HID plugged: ${pluggedDevice.name}")
                } else {
                    try {
                        val devices = hidDevice?.connectedDevices
                        if (!devices.isNullOrEmpty()) {
                            connectedDevice = devices[0]
                            showToast("Bluetooth HID active: ${devices[0].name}")
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, connectionState: Int) {
            if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = device
                showToast("Bluetooth HID Connected: ${device.name}")
            } else if (connectionState == BluetoothProfile.STATE_DISCONNECTED) {
                if (connectedDevice == device) {
                    connectedDevice = null
                    showToast("Bluetooth HID Disconnected")
                }
            }
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    init {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter != null) {
            adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_DEVICE)
        }
    }

    private fun registerApp() {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Native Gamepad",
            "Virtual Gamepad",
            "NativeGamepad",
            0x08.toByte(), // Gamepad subclass
            HidDescriptor.GAMEPAD_DESCRIPTOR
        )
        hidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        thread = Thread {
            var lastSendTime = System.currentTimeMillis()
            while (isRunning) {
                val now = System.currentTimeMillis()
                val stateChanged = !state.equalsExceptSeq(lastSentState)
                val heartbeatDue = (now - lastSendTime) > 300 

                if (stateChanged || heartbeatDue) {
                    sendReport()
                    lastSentState.copyFrom(state)
                    lastSendTime = now
                }
                try {
                    Thread.sleep(16)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        thread?.start()
    }

    private fun sendReport() {
        val device = connectedDevice ?: return
        val hid = hidDevice ?: return

        val report = ByteArray(13)

        // Map Buttons (16 bits). Current mask:
        // A=0x1000, B=0x2000, X=0x4000, Y=0x8000
        // Dpad: UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08 (we will remove these from standard buttons and map to Hat Switch)
        // Start=0x10, Back=0x20, L3=0x40, R3=0x80, LB=0x100, RB=0x200
        
        // We will map exactly 16 bits.
        // Bit 0: A
        // Bit 1: B
        // Bit 2: X
        // Bit 3: Y
        // Bit 4: LB
        // Bit 5: RB
        // Bit 6: Back
        // Bit 7: Start
        // Bit 8: L3
        // Bit 9: R3
        var mappedBtns = 0
        if ((state.buttons and 0x1000) != 0) mappedBtns = mappedBtns or (1 shl 0) // A
        if ((state.buttons and 0x2000) != 0) mappedBtns = mappedBtns or (1 shl 1) // B
        if ((state.buttons and 0x4000) != 0) mappedBtns = mappedBtns or (1 shl 2) // X
        if ((state.buttons and 0x8000) != 0) mappedBtns = mappedBtns or (1 shl 3) // Y
        if ((state.buttons and 0x0100) != 0) mappedBtns = mappedBtns or (1 shl 4) // LB
        if ((state.buttons and 0x0200) != 0) mappedBtns = mappedBtns or (1 shl 5) // RB
        if ((state.buttons and 0x0020) != 0) mappedBtns = mappedBtns or (1 shl 6) // Back/Select
        if ((state.buttons and 0x0010) != 0) mappedBtns = mappedBtns or (1 shl 7) // Start
        if ((state.buttons and 0x0040) != 0) mappedBtns = mappedBtns or (1 shl 8) // L3
        if ((state.buttons and 0x0080) != 0) mappedBtns = mappedBtns or (1 shl 9) // R3

        report[0] = (mappedBtns and 0xFF).toByte()
        report[1] = ((mappedBtns shr 8) and 0xFF).toByte()

        // Map D-Pad to Hat Switch (1-8, 0 is null state)
        val up = (state.buttons and 0x01) != 0
        val down = (state.buttons and 0x02) != 0
        val left = (state.buttons and 0x04) != 0
        val right = (state.buttons and 0x08) != 0
        
        var hat = 0
        if (up && right) hat = 2
        else if (down && right) hat = 4
        else if (down && left) hat = 6
        else if (up && left) hat = 8
        else if (up) hat = 1
        else if (right) hat = 3
        else if (down) hat = 5
        else if (left) hat = 7

        report[2] = hat.toByte()

        // Axes (16-bit)
        // Left stick X/Y (indices 3-4, 5-6)
        report[3] = (state.lx.toInt() and 0xFF).toByte()
        report[4] = ((state.lx.toInt() shr 8) and 0xFF).toByte()
        report[5] = (state.ly.toInt() and 0xFF).toByte()
        report[6] = ((state.ly.toInt() shr 8) and 0xFF).toByte()
        
        // Right stick X/Y mapped to Z/Rz (indices 7-8, 9-10)
        report[7] = (state.rx.toInt() and 0xFF).toByte()
        report[8] = ((state.rx.toInt() shr 8) and 0xFF).toByte()
        report[9] = (state.ry.toInt() and 0xFF).toByte()
        report[10] = ((state.ry.toInt() shr 8) and 0xFF).toByte()

        // Triggers (8-bit)
        // LT (Brake) index 11, RT (Accelerator) index 12
        report[11] = state.lt
        report[12] = state.rt

        // Note: We use ID=1 because we added Report ID 1 to the HID Descriptor
        hid.sendReport(device, 1, report)
    }

    fun stop() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }

    fun destroy() {
        stop()
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (hidDevice != null && adapter != null) {
            adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
        }
    }
}
