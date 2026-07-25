package com.zx.smartring.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class NearbyBleDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isSq666Candidate: Boolean
)

enum class BleConnectionState {
    DISCONNECTED,
    CONNECTING,
    MTU_NEGOTIATING,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    WAITING_INITIAL_COUNT,
    READY
}

enum class BleProtocolError {
    MTU_NEGOTIATION_FAILED,
    MTU_TOO_SMALL,
    SERVICE_DISCOVERY_FAILED,
    INCOMPATIBLE_DEVICE,
    NOTIFICATION_SETUP_FAILED,
    OPERATION_TIMEOUT
}

enum class BleResetError {
    NOT_READY,
    BUSY,
    WRITE_FAILED,
    TIMEOUT
}

interface BleConnectionListener {
    fun onConnecting(deviceName: String)
    fun onGattConnected(deviceName: String)
    fun onProtocolStateChanged(state: BleConnectionState)
    fun onProtocolReady(deviceName: String)
    fun onDeviceCount(currentCount: Int, isInitialReport: Boolean)
    fun onResetSucceeded()
    fun onResetFailed(error: BleResetError)
    fun onProtocolError(deviceName: String, error: BleProtocolError)
    fun onDisconnected(deviceName: String?, wasReady: Boolean)
    fun onConnectionFailed(deviceName: String, status: Int)
}

class BleConnectionManager(
    context: Context,
    private val listener: BleConnectionListener
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val frameParser = Sq666FrameStreamParser()
    private val bluetoothAdapter
        get() = bluetoothManager.adapter

    private var scanCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var notificationDescriptor: BluetoothGattDescriptor? = null
    private var activeDeviceName: String? = null
    private var lastReportFingerprint: Pair<Long, Int>? = null
    private var receivedInitialReport = false
    private var notificationWriteInProgress = false
    private var resetPending = false
    private var resetWriteSucceeded = false
    private var resetAcknowledged = false
    private var protocolTimeout: Runnable? = null
    private var resetTimeout: Runnable? = null

    @Volatile
    var connectionState = BleConnectionState.DISCONNECTED
        private set

    fun isSupported(): Boolean = bluetoothAdapter != null

    @SuppressLint("MissingPermission")
    fun isEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan(
        unnamedDeviceLabel: String,
        onDeviceFound: (NearbyBleDevice) -> Unit,
        onScanFailed: (Int) -> Unit
    ): Boolean {
        stopScan()
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return false
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!result.isConnectable) return
                val advertisedName = result.scanRecord?.deviceName
                val systemName = runCatching { result.device.name }.getOrNull()
                val resolvedName = advertisedName?.takeIf { it.isNotBlank() }
                    ?: systemName?.takeIf { it.isNotBlank() }
                    ?: unnamedDeviceLabel
                val advertisedServices = result.scanRecord?.serviceUuids.orEmpty()
                val isSq666Candidate = advertisedServices.any { it.uuid == ADVERTISED_SERVICE_UUID } ||
                    resolvedName.startsWith(SQ666_NAME_PREFIX, ignoreCase = true)
                onDeviceFound(
                    NearbyBleDevice(
                        device = result.device,
                        name = resolvedName,
                        address = result.device.address,
                        rssi = result.rssi,
                        isSq666Candidate = isSq666Candidate
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                scanCallback = null
                onScanFailed(errorCode)
            }
        }
        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return runCatching {
            scanner.startScan(emptyList(), settings, callback)
            true
        }.getOrElse {
            scanCallback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        val callback = scanCallback ?: return
        scanCallback = null
        runCatching { bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: NearbyBleDevice) {
        stopScan()
        closeGatt()
        resetProtocolSession()
        activeDeviceName = device.name
        setState(BleConnectionState.CONNECTING)
        listener.onConnecting(device.name)
        scheduleProtocolTimeout(BleProtocolError.OPERATION_TIMEOUT, CONNECTION_TIMEOUT_MS)
        bluetoothGatt = runCatching {
            device.device.connectGatt(
                appContext,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        }.getOrNull()
        if (bluetoothGatt == null) {
            cancelProtocolTimeout()
            setState(BleConnectionState.DISCONNECTED)
            listener.onConnectionFailed(device.name, CONNECTION_START_FAILED)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        val gatt = bluetoothGatt
        if (gatt == null || connectionState == BleConnectionState.CONNECTING) {
            val previousName = activeDeviceName
            val wasReady = connectionState == BleConnectionState.READY
            closeGatt()
            activeDeviceName = null
            setState(BleConnectionState.DISCONNECTED)
            listener.onDisconnected(previousName, wasReady)
            return
        }
        runCatching { gatt.disconnect() }.onFailure {
            val previousName = activeDeviceName
            val wasReady = connectionState == BleConnectionState.READY
            closeGatt()
            activeDeviceName = null
            setState(BleConnectionState.DISCONNECTED)
            listener.onDisconnected(previousName, wasReady)
        }
    }

    @SuppressLint("MissingPermission")
    fun resetDeviceCount(): Boolean {
        if (connectionState != BleConnectionState.READY) {
            listener.onResetFailed(BleResetError.NOT_READY)
            return false
        }
        if (resetPending || notificationWriteInProgress) {
            listener.onResetFailed(BleResetError.BUSY)
            return false
        }
        val gatt = bluetoothGatt
        val characteristic = txCharacteristic
        if (gatt == null || characteristic == null) {
            listener.onResetFailed(BleResetError.NOT_READY)
            return false
        }

        resetPending = true
        resetWriteSucceeded = false
        resetAcknowledged = false
        val frame = Sq666Protocol.buildResetCountFrame()
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            failReset(BleResetError.WRITE_FAILED)
            return false
        }
        scheduleResetTimeout()
        return true
    }

    fun close() {
        stopScan()
        closeGatt()
        activeDeviceName = null
        connectionState = BleConnectionState.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (bluetoothGatt !== gatt) {
                runCatching { gatt.close() }
                return
            }
            val name = activeDeviceName.orEmpty()
            when {
                status != BluetoothGatt.GATT_SUCCESS -> {
                    runCatching { gatt.close() }
                    bluetoothGatt = null
                    cancelAllTimeouts()
                    setState(BleConnectionState.DISCONNECTED)
                    activeDeviceName = null
                    mainHandler.post { listener.onConnectionFailed(name, status) }
                }
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    cancelProtocolTimeout()
                    setState(BleConnectionState.MTU_NEGOTIATING)
                    mainHandler.post { listener.onGattConnected(name) }
                    scheduleProtocolTimeout(BleProtocolError.MTU_NEGOTIATION_FAILED)
                    if (!runCatching { gatt.requestMtu(REQUESTED_MTU) }.getOrDefault(false)) {
                        failInitialization(BleProtocolError.MTU_NEGOTIATION_FAILED)
                    }
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    val wasReady = connectionState == BleConnectionState.READY
                    runCatching { gatt.close() }
                    bluetoothGatt = null
                    cancelAllTimeouts()
                    setState(BleConnectionState.DISCONNECTED)
                    activeDeviceName = null
                    mainHandler.post { listener.onDisconnected(name, wasReady) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (bluetoothGatt !== gatt) return
            cancelProtocolTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failInitialization(BleProtocolError.MTU_NEGOTIATION_FAILED)
                return
            }
            if (mtu < MINIMUM_MTU) {
                failInitialization(BleProtocolError.MTU_TOO_SMALL)
                return
            }
            setState(BleConnectionState.DISCOVERING_SERVICES)
            scheduleProtocolTimeout(BleProtocolError.SERVICE_DISCOVERY_FAILED)
            if (!runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                failInitialization(BleProtocolError.SERVICE_DISCOVERY_FAILED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (bluetoothGatt !== gatt) return
            cancelProtocolTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failInitialization(BleProtocolError.SERVICE_DISCOVERY_FAILED)
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            val tx = service?.getCharacteristic(TX_UUID)
            val rx = service?.getCharacteristic(RX_UUID)
            val cccd = rx?.getDescriptor(CCCD_UUID)
            if (service == null || tx == null || rx == null || cccd == null) {
                failInitialization(BleProtocolError.INCOMPATIBLE_DEVICE)
                return
            }
            txCharacteristic = tx
            rxCharacteristic = rx
            notificationDescriptor = cccd
            if (!enableNotifications()) {
                failInitialization(BleProtocolError.NOTIFICATION_SETUP_FAILED)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (bluetoothGatt !== gatt || descriptor.uuid != CCCD_UUID) return
            notificationWriteInProgress = false
            cancelProtocolTimeout()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failInitialization(BleProtocolError.NOTIFICATION_SETUP_FAILED)
                return
            }
            if (receivedInitialReport) {
                setState(BleConnectionState.READY)
                mainHandler.post { listener.onProtocolReady(activeDeviceName.orEmpty()) }
            } else {
                setState(BleConnectionState.WAITING_INITIAL_COUNT)
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == RX_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value?.copyOf() ?: ByteArray(0)
                mainHandler.post {
                    if (bluetoothGatt === gatt) handleNotification(value)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == RX_UUID) {
                val copiedValue = value.copyOf()
                mainHandler.post {
                    if (bluetoothGatt === gatt) handleNotification(copiedValue)
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != TX_UUID) return
            mainHandler.post {
                if (bluetoothGatt !== gatt || !resetPending) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failReset(BleResetError.WRITE_FAILED)
                    return@post
                }
                resetWriteSucceeded = true
                completeResetIfConfirmed()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(): Boolean {
        if (notificationWriteInProgress) return false
        val gatt = bluetoothGatt ?: return false
        val rx = rxCharacteristic ?: return false
        val cccd = notificationDescriptor ?: return false
        val previousState = connectionState
        setState(BleConnectionState.ENABLING_NOTIFICATIONS)
        if (!runCatching { gatt.setCharacteristicNotification(rx, true) }.getOrDefault(false)) {
            setState(previousState)
            return false
        }
        notificationWriteInProgress = true
        scheduleProtocolTimeout(BleProtocolError.NOTIFICATION_SETUP_FAILED)
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(
                cccd,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
        if (!started) {
            notificationWriteInProgress = false
            cancelProtocolTimeout()
            setState(previousState)
        }
        return started
    }

    private fun handleNotification(value: ByteArray) {
        frameParser.append(value).forEach(::handleFrame)
    }

    private fun handleFrame(frame: Sq666Frame) {
        when (frame.command) {
            Sq666Protocol.COMMAND_COUNT_REPORT -> {
                Sq666Protocol.parseCountReport(frame)?.let(::handleCountReport)
            }
            Sq666Protocol.COMMAND_ACTIVATE_TASK -> {
                val acknowledgedCount = Sq666Protocol.parseAcknowledgedCount(frame)
                if (resetPending && acknowledgedCount == 0L) {
                    resetAcknowledged = true
                    completeResetIfConfirmed()
                }
            }
        }
    }

    private fun handleCountReport(report: Sq666CountReport) {
        val fingerprint = report.deviceTimestamp to report.currentCount
        if (fingerprint == lastReportFingerprint) return
        lastReportFingerprint = fingerprint
        val initial = !receivedInitialReport
        receivedInitialReport = true
        if (connectionState != BleConnectionState.READY) {
            setState(BleConnectionState.READY)
            mainHandler.post { listener.onProtocolReady(activeDeviceName.orEmpty()) }
        }
        mainHandler.post { listener.onDeviceCount(report.currentCount, initial) }
    }

    private fun completeResetIfConfirmed() {
        if (!resetPending || !resetWriteSucceeded || !resetAcknowledged) return
        cancelResetTimeout()
        resetPending = false
        resetWriteSucceeded = false
        resetAcknowledged = false
        lastReportFingerprint = null
        mainHandler.post { listener.onResetSucceeded() }
    }

    private fun failReset(error: BleResetError) {
        cancelResetTimeout()
        resetPending = false
        resetWriteSucceeded = false
        resetAcknowledged = false
        mainHandler.post { listener.onResetFailed(error) }
    }

    private fun scheduleResetTimeout() {
        cancelResetTimeout()
        val timeout = Runnable {
            if (resetPending) failReset(BleResetError.TIMEOUT)
        }
        resetTimeout = timeout
        mainHandler.postDelayed(timeout, RESET_TIMEOUT_MS)
    }

    private fun cancelResetTimeout() {
        resetTimeout?.let(mainHandler::removeCallbacks)
        resetTimeout = null
    }

    @SuppressLint("MissingPermission")
    private fun failInitialization(error: BleProtocolError) {
        val name = activeDeviceName.orEmpty()
        closeGatt()
        activeDeviceName = null
        setState(BleConnectionState.DISCONNECTED)
        mainHandler.post { listener.onProtocolError(name, error) }
    }

    private fun scheduleProtocolTimeout(
        error: BleProtocolError,
        delayMillis: Long = PROTOCOL_STEP_TIMEOUT_MS
    ) {
        cancelProtocolTimeout()
        val timeout = Runnable {
            if (connectionState != BleConnectionState.DISCONNECTED) {
                failInitialization(error)
            }
        }
        protocolTimeout = timeout
        mainHandler.postDelayed(timeout, delayMillis)
    }

    private fun cancelProtocolTimeout() {
        protocolTimeout?.let(mainHandler::removeCallbacks)
        protocolTimeout = null
    }

    private fun cancelAllTimeouts() {
        cancelProtocolTimeout()
        cancelResetTimeout()
    }

    private fun setState(state: BleConnectionState) {
        connectionState = state
        mainHandler.post { listener.onProtocolStateChanged(state) }
    }

    private fun resetProtocolSession() {
        frameParser.reset()
        txCharacteristic = null
        rxCharacteristic = null
        notificationDescriptor = null
        notificationWriteInProgress = false
        receivedInitialReport = false
        lastReportFingerprint = null
        resetPending = false
        resetWriteSucceeded = false
        resetAcknowledged = false
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        cancelAllTimeouts()
        notificationWriteInProgress = false
        resetPending = false
        val gatt = bluetoothGatt
        bluetoothGatt = null
        txCharacteristic = null
        rxCharacteristic = null
        notificationDescriptor = null
        frameParser.reset()
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private companion object {
        val ADVERTISED_SERVICE_UUID: UUID =
            UUID.fromString("0000fef5-0000-1000-8000-00805f9b34fb")
        val SERVICE_UUID: UUID =
            UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
        val TX_UUID: UUID =
            UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb")
        val RX_UUID: UUID =
            UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val SQ666_NAME_PREFIX = "SQ666"
        const val REQUESTED_MTU = 185
        const val MINIMUM_MTU = 26
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val PROTOCOL_STEP_TIMEOUT_MS = 8_000L
        const val RESET_TIMEOUT_MS = 3_000L
        const val CONNECTION_START_FAILED = -1
    }
}
