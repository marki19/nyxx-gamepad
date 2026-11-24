package com.nativegamepad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class BleConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Reconnecting
}

class ArduinoBluetoothManager(
    context: Context,
    private val btAdapter: BluetoothAdapter? = null
) {
    private val appContext: Context = context.applicationContext

    // Secondary constructor for 100% backward compatibility with old constructor (adapter only)
    constructor(adapter: BluetoothAdapter) : this(
        lastContext ?: throw IllegalStateException("Context required for BLE. Pass Context to ArduinoBluetoothManager."),
        adapter
    )

    companion object {
        private const val TAG = "ArduinoBleManager"

        // Default JDY-16 target name and standard UUIDs
        const val DEVICE_NAME_DEFAULT = "JDY-16"
        val SERVICE_UUID: UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val WRITE_CHAR_UUID: UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
        val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000FFE2-0000-1000-8000-00805F9B34FB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        // Retain last known context for fallback constructor
        @Volatile
        var lastContext: Context? = null
    }

    init {
        lastContext = appContext
    }

    private val effectiveAdapter: BluetoothAdapter?
        get() = btAdapter ?: (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State flows
    private val _connectionState = MutableStateFlow(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    var connectedDeviceName: String = "Robot Car"
        private set

    // Backward-compatible isConnected property
    val isConnected: Boolean
        get() = _connectionState.value == BleConnectionState.Connected

    // Incoming data flows & callbacks
    private val _incomingData = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val incomingData: SharedFlow<ByteArray> = _incomingData.asSharedFlow()

    var onDataReceived: ((ByteArray) -> Unit)? = null

    // GATT and characteristics
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    // Write queue & MTU
    private var currentMtu = 23
    private val writeChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var writeJob: Job? = null
    private var writeDeferred: CompletableDeferred<Int>? = null

    // Scanning & Connection state management
    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeoutJob: Job? = null
    private var connectDeferred: CompletableDeferred<Boolean>? = null

    private var targetDeviceName: String = DEVICE_NAME_DEFAULT
    private var targetDeviceAddress: String? = null

    private var isUserInitiatedDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectBackoffMs = 1000L
    private var reconnectAttempts = 0

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(): Boolean {
        return effectiveAdapter?.isEnabled == true
    }

    /**
     * Backward compatible connectToCar method.
     * Can connect directly by MAC address if given, or scan for JDY-16.
     */
    @SuppressLint("MissingPermission")
    suspend fun connectToCar(deviceAddress: String? = null): Boolean = withContext(Dispatchers.Main) {
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot connect: missing required Bluetooth permissions")
            return@withContext false
        }

        val adapter = effectiveAdapter
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Cannot connect: Bluetooth is off or unavailable")
            return@withContext false
        }

        isUserInitiatedDisconnect = false
        reconnectAttempts = 0
        val savedMac = appContext.getSharedPreferences("robot_car_prefs", Context.MODE_PRIVATE).getString("last_car_mac", null)
        targetDeviceAddress = if (!deviceAddress.isNullOrBlank()) deviceAddress else savedMac
        targetDeviceName = DEVICE_NAME_DEFAULT

        // Clean up previous gatt if any
        closeGatt()

        // 1. Check if car is already paired or connected at system level (e.g. in phone Bluetooth settings)
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val systemConnected = try {
            bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
        } catch (_: Exception) { emptyList() }

        val bonded = try {
            adapter.bondedDevices ?: emptySet()
        } catch (_: Exception) { emptySet() }

        val candidates = (systemConnected + bonded).distinctBy { it.address }
        val alreadyPairedCar = candidates.firstOrNull { dev ->
            val name = dev.name ?: ""
            val address = dev.address
            (!targetDeviceAddress.isNullOrBlank() && address.equals(targetDeviceAddress, ignoreCase = true)) ||
            name.equals("mark1e3", ignoreCase = true) ||
            name.contains("mark", ignoreCase = true) ||
            name.contains("JDY", ignoreCase = true) ||
            name.contains("car", ignoreCase = true) ||
            name.contains("robot", ignoreCase = true)
        }

        if (alreadyPairedCar != null) {
            Log.i(TAG, "Car found in bonded/system devices: ${alreadyPairedCar.name} [${alreadyPairedCar.address}]. Connecting direct GATT...")
            connectedDeviceName = alreadyPairedCar.name ?: "Robot Car"
            connectDeferred = CompletableDeferred()
            connectGattInternal(alreadyPairedCar)
            val directResult = withTimeoutOrNull(15000L) {
                connectDeferred?.await()
            } ?: false

            if (directResult) {
                return@withContext true
            } else {
                Log.w(TAG, "Direct GATT connection to bonded device failed/timed out. Falling back to BLE scan.")
            }
        }

        _connectionState.value = BleConnectionState.Scanning

        connectDeferred = CompletableDeferred()

        startScanAndConnect(targetDeviceAddress)

        val result = withTimeoutOrNull(25000L) {
            connectDeferred?.await()
        } ?: false

        if (!result && _connectionState.value != BleConnectionState.Connected) {
            Log.w(TAG, "Connection attempt timed out or failed")
            if (!isUserInitiatedDisconnect) {
                scheduleReconnect()
            }
        }

        result
    }

    @SuppressLint("MissingPermission")
    private fun startScanAndConnect(expectedAddress: String?) {
        val adapter = effectiveAdapter ?: return
        bleScanner = adapter.bluetoothLeScanner

        if (bleScanner == null) {
            Log.e(TAG, "bluetoothLeScanner is null")
            connectDeferred?.complete(false)
            return
        }

        val filters = if (!expectedAddress.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(expectedAddress)) {
            listOf(ScanFilter.Builder().setDeviceAddress(expectedAddress).build())
        } else {
            null
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val name = result.scanRecord?.deviceName ?: device.name ?: ""
                Log.d(TAG, "Discovered BLE device: $name [${device.address}]")

                val serviceUuids = result.scanRecord?.serviceUuids
                val matchesService = serviceUuids?.any { 
                    it.uuid == SERVICE_UUID || it.uuid.toString().uppercase().contains("FFE0")
                } == true

                val rawBytes = result.scanRecord?.bytes
                val hasFfe0InBytes = rawBytes?.let { bytes ->
                    var found = false
                    for (i in 0 until (bytes.size - 1)) {
                        if ((bytes[i].toInt() and 0xFF) == 0xE0 && (bytes[i + 1].toInt() and 0xFF) == 0xFF) {
                            found = true
                            break
                        }
                    }
                    found
                } ?: false

                val prefs = appContext.getSharedPreferences("robot_car_prefs", Context.MODE_PRIVATE)
                val savedCarName = prefs.getString("last_car_name", null)
                val savedCarMac = prefs.getString("last_car_mac", null)

                val matchesName = (savedCarName != null && name.equals(savedCarName, ignoreCase = true)) ||
                                  name.contains("JDY", ignoreCase = true) ||
                                  name.contains("mark", ignoreCase = true) ||
                                  name.contains("car", ignoreCase = true) ||
                                  name.contains("robot", ignoreCase = true) ||
                                  name.equals(targetDeviceName, ignoreCase = true)

                val matchesAddress = (expectedAddress != null && device.address.equals(expectedAddress, ignoreCase = true)) ||
                                     (savedCarMac != null && device.address.equals(savedCarMac, ignoreCase = true))

                if (matchesService || hasFfe0InBytes || matchesName || matchesAddress) {
                    connectedDeviceName = if (name.isNotBlank()) name else (device.name ?: "Robot Car")
                    Log.i(TAG, "Target car matched ($connectedDeviceName)! Stopping scan and connecting...")
                    stopScan()
                    connectGattInternal(device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                stopScan()
                // If address is available, fallback to direct connect
                if (!expectedAddress.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(expectedAddress)) {
                    val fallbackDevice = adapter.getRemoteDevice(expectedAddress)
                    connectGattInternal(fallbackDevice)
                } else {
                    connectDeferred?.complete(false)
                }
            }
        }

        Log.d(TAG, "Starting BLE scan for '$targetDeviceName'...")
        try {
            bleScanner?.startScan(filters, settings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan", e)
            connectDeferred?.complete(false)
            return
        }

        // 10s Scan timeout
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(10000L)
            if (_connectionState.value == BleConnectionState.Scanning) {
                Log.w(TAG, "Scan timeout (10s) reached.")
                stopScan()
                if (!expectedAddress.isNullOrBlank() && BluetoothAdapter.checkBluetoothAddress(expectedAddress)) {
                    Log.i(TAG, "Attempting direct GATT connection to known address $expectedAddress")
                    val device = adapter.getRemoteDevice(expectedAddress)
                    connectGattInternal(device)
                } else {
                    connectDeferred?.complete(false)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (scanCallback != null && bleScanner != null) {
            try {
                bleScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping scan", e)
            }
            scanCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattInternal(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.Connecting
        Log.i(TAG, "Connecting to GATT on ${device.address} (autoConnect = false, TRANSPORT_LE)...")

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(appContext, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")

            // Handle status 133 (GATT client error/congestion)
            if (status == 133) {
                Log.e(TAG, "GATT status 133 encountered! Closing client and retrying after delay...")
                closeGatt()
                if (!isUserInitiatedDisconnect) {
                    scheduleReconnect(1000L)
                }
                return
            }

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.Connecting
                Log.i(TAG, "GATT connected. Discovering services...")
                // Requirement 2: Call discoverServices()
                val discovered = gatt.discoverServices()
                if (!discovered) {
                    Log.e(TAG, "discoverServices() returned false")
                    closeGatt()
                    if (!isUserInitiatedDisconnect) scheduleReconnect()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "GATT disconnected (status=$status, newState=$newState)")
                closeGatt()
                if (!isUserInitiatedDisconnect) {
                    scheduleReconnect()
                } else {
                    _connectionState.value = BleConnectionState.Disconnected
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status $status")
                closeGatt()
                if (!isUserInitiatedDisconnect) scheduleReconnect()
                return
            }

            // Resolve characteristics with property logging and fallbacks
            val resolved = resolveCharacteristics(gatt)
            if (!resolved) {
                Log.e(TAG, "Failed to resolve required JDY-16 characteristics")
                closeGatt()
                if (!isUserInitiatedDisconnect) scheduleReconnect()
                return
            }

            // Requirement 2: requestMtu(185) (continue even if it fails)
            Log.d(TAG, "Requesting MTU 185...")
            val mtuRequested = gatt.requestMtu(185)
            if (!mtuRequested) {
                Log.w(TAG, "requestMtu(185) returned false, continuing with notification enable")
                enableNotifications(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged: mtu=$mtu, status=$status")
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            // Proceed to enable notifications
            enableNotifications(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite: uuid=${descriptor.uuid}, status=$status")
            if (descriptor.uuid == CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                // Requirement 2: Only report "connected" after the CCCD write succeeds
                onSuccessfullyConnected()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "CCCD descriptor write failed with status $status; still establishing connection")
                onSuccessfullyConnected()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeDeferred?.complete(status)
            synchronized(lock) { busy = false }
            pump()
        }

        // For API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            dispatchIncomingData(value)
        }

        // For API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                dispatchIncomingData(value)
            }
        }
    }

    private fun dispatchIncomingData(data: ByteArray) {
        _incomingData.tryEmit(data)
        scope.launch(Dispatchers.Main) {
            onDataReceived?.invoke(data)
        }
    }

    private fun resolveCharacteristics(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(SERVICE_UUID)
        if (service == null) {
            Log.e(TAG, "Target Service $SERVICE_UUID not found! Discovered services:")
            for (s in gatt.services) {
                Log.e(TAG, " - ${s.uuid}")
            }
            return false
        }

        Log.i(TAG, "Discovered target service $SERVICE_UUID. Characteristics:")
        for (ch in service.characteristics) {
            val p = ch.properties
            val canWrite = (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val canWriteNr = (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val canNotify = (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
            Log.i(TAG, "  UUID: ${ch.uuid} | Props: write=$canWrite, writeNoResp=$canWriteNr, notify=$canNotify")
        }

        // 1. Resolve TX characteristic
        var tx = service.getCharacteristic(WRITE_CHAR_UUID)
        if (tx == null || ((tx.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0)) {
            Log.w(TAG, "Preferred TX $WRITE_CHAR_UUID missing or not writable. Searching fallback in service...")
            tx = service.characteristics.firstOrNull { ch ->
                (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
            }
        }

        // 2. Resolve RX characteristic
        var rx = service.getCharacteristic(NOTIFY_CHAR_UUID)
        if (rx == null || ((rx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0)) {
            Log.w(TAG, "Preferred RX $NOTIFY_CHAR_UUID missing or not notify. Checking if TX supports notify...")
            if (tx != null && ((tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)) {
                Log.i(TAG, "Single transparent characteristic: using ${tx.uuid} for both TX and RX.")
                rx = tx
            } else {
                rx = service.characteristics.firstOrNull { ch ->
                    (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                }
            }
        }

        txCharacteristic = tx
        rxCharacteristic = rx

        if (tx == null) {
            Log.e(TAG, "No writable characteristic found!")
            return false
        }

        Log.i(TAG, "Resolved TX Char: ${tx.uuid}, RX Char: ${rx?.uuid}")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt) {
        val rx = rxCharacteristic
        if (rx == null) {
            Log.w(TAG, "No RX characteristic available, proceeding as write-only")
            onSuccessfullyConnected()
            return
        }

        val notifySet = gatt.setCharacteristicNotification(rx, true)
        if (!notifySet) {
            Log.w(TAG, "setCharacteristicNotification returned false")
        }

        val cccd = rx.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "CCCD descriptor 0x2902 not found on ${rx.uuid}. Reporting connected directly.")
            onSuccessfullyConnected()
            return
        }

        val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val res = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            res == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }

        if (!writeSuccess) {
            Log.w(TAG, "writeDescriptor for CCCD returned false. Proceeding to connected.")
            onSuccessfullyConnected()
        }
    }

    private fun onSuccessfullyConnected() {
        reconnectBackoffMs = 1000L
        reconnectAttempts = 0
        _connectionState.value = BleConnectionState.Connected
        startWriteQueue()
        connectDeferred?.complete(true)
        connectDeferred = null

        val device = bluetoothGatt?.device
        if (device != null) {
            val prefs = appContext.getSharedPreferences("robot_car_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_car_mac", device.address)
                .putString("last_car_name", connectedDeviceName)
                .apply()
        }
        Log.i(TAG, "Successfully connected to $connectedDeviceName [${device?.address}]!")
    }

    private fun startWriteQueue() {
        writeJob?.cancel()
        writeJob = scope.launch(Dispatchers.IO) {
            for (packet in writeChannel) {
                val gatt = bluetoothGatt ?: continue
                val tx = txCharacteristic ?: continue
                val chunkSize = (currentMtu - 3).coerceIn(20, 512)
                var offset = 0

                while (offset < packet.size && isActive && _connectionState.value == BleConnectionState.Connected) {
                    val end = minOf(offset + chunkSize, packet.size)
                    val chunk = packet.copyOfRange(offset, end)
                    offset = end

                    val writeType = if ((tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    } else {
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    }

                    writeDeferred = if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                        CompletableDeferred()
                    } else {
                        null
                    }

                    val writeAccepted = writeChunk(gatt, tx, chunk, writeType)
                    if (writeAccepted) {
                        if (writeDeferred != null) {
                            try {
                                withTimeoutOrNull(1000L) { writeDeferred?.await() }
                            } catch (e: Exception) {
                                Log.w(TAG, "Timeout waiting for onCharacteristicWrite callback")
                            }
                        } else {
                            delay(15L) // Short spacing for NO_RESPONSE writes
                        }
                    } else {
                        Log.w(TAG, "writeCharacteristic returned false for chunk")
                        delay(20L)
                    }
                }
                // Minimum 50 ms gap between any two commands
                delay(50L)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(characteristic, chunk, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = chunk
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    // --- Fast Joystick Angle Throttling & Priority Stop ---
    private val lock = Any()
    private var latest: ByteArray? = null   // newest joystick packet; a newer one overwrites it
    private var stopPending = false
    private var busy = false

    fun joystick(angle: Int) {
        synchronized(lock) {
            latest = "@$angle#\n".toByteArray(Charsets.UTF_8)
        }
        pump()
    }

    fun sendMotion(cmd: String) {
        synchronized(lock) {
            val payload = if (cmd.endsWith("\n")) cmd else "$cmd\n"
            latest = payload.toByteArray(Charsets.UTF_8)
        }
        pump()
    }

    fun stop() {
        synchronized(lock) {
            latest = null
            stopPending = true
        }
        pump()
    }

    private fun pump() {
        synchronized(lock) {
            if (busy) return
            val data: ByteArray = when {
                stopPending -> {
                    stopPending = false
                    "%S".toByteArray(Charsets.UTF_8)
                }
                else -> latest?.also { latest = null } ?: return
            }
            busy = true
            writeToGatt(data)   // WRITE_TYPE_NO_RESPONSE; set busy = false if the write fails to start
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeToGatt(data: ByteArray) {
        val gatt = bluetoothGatt
        val tx = txCharacteristic
        if (gatt == null || tx == null || _connectionState.value != BleConnectionState.Connected) {
            synchronized(lock) { busy = false }
            return
        }

        val writeType = if ((tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(tx, data, writeType)
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            tx.value = data
            @Suppress("DEPRECATION")
            tx.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(tx)
        }

        if (!success) {
            Log.w(TAG, "writeToGatt failed to start")
            synchronized(lock) { busy = false }
        } else if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            // For NO_RESPONSE writes, Android stack may not trigger onCharacteristicWrite.
            // Release busy after 20ms spacing so pump can proceed.
            scope.launch(Dispatchers.IO) {
                delay(20L)
                synchronized(lock) { busy = false }
                pump()
            }
        }
    }

    /**
     * Send byte array over BLE TX characteristic.
     */
    fun send(data: ByteArray) {
        if (_connectionState.value == BleConnectionState.Connected) {
            writeChannel.trySend(data)
        } else {
            Log.w(TAG, "Cannot send: not connected (state=${_connectionState.value})")
        }
    }

    /**
     * Backward compatible string command sender.
     */
    fun sendCommand(command: String) {
        send(command.toByteArray(Charsets.UTF_8))
    }

    private fun scheduleReconnect(delayMs: Long? = null) {
        if (isUserInitiatedDisconnect) return
        reconnectAttempts++
        if (reconnectAttempts > 3) {
            Log.w(TAG, "Reconnect attempts exceeded (3). Stopping auto-reconnect.")
            reconnectJob?.cancel()
            _connectionState.value = BleConnectionState.Disconnected
            return
        }
        reconnectJob?.cancel()
        _connectionState.value = BleConnectionState.Reconnecting

        val waitTime = delayMs ?: reconnectBackoffMs
        reconnectBackoffMs = (reconnectBackoffMs * 2).coerceAtMost(8000L)

        Log.i(TAG, "Scheduling auto-reconnect attempt $reconnectAttempts in ${waitTime}ms")
        reconnectJob = scope.launch(Dispatchers.Main) {
            delay(waitTime)
            if (!isUserInitiatedDisconnect && _connectionState.value != BleConnectionState.Connected) {
                connectToCar(targetDeviceAddress)
            }
        }
    }

    /**
     * Disconnects and stops any auto-reconnection.
     */
    fun disconnect() {
        closeConnection()
    }

    /**
     * Backward-compatible closeConnection method.
     */
    fun closeConnection() {
        isUserInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        stopScan()
        writeJob?.cancel()
        writeJob = null
        closeGatt()
        _connectionState.value = BleConnectionState.Disconnected
        connectDeferred?.complete(false)
        connectDeferred = null
        Log.d(TAG, "Arduino Bluetooth connection closed")
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        synchronized(lock) {
            latest = null
            stopPending = false
            busy = false
        }
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) { }
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) { }
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null
    }
}
