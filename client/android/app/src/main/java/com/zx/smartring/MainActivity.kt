package com.zx.smartring

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.LocaleManager
import android.app.TimePickerDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.LocaleList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.zx.smartring.auth.LoginActivity
import com.zx.smartring.auth.SessionStore
import com.zx.smartring.ble.BleConnectionListener
import com.zx.smartring.ble.BleConnectionManager
import com.zx.smartring.ble.BleConnectionState
import com.zx.smartring.ble.BleProtocolError
import com.zx.smartring.ble.BleResetError
import com.zx.smartring.ble.NearbyBleDevice
import com.zx.smartring.reminder.RecitationReminderScheduler
import com.zx.smartring.settings.AppSettingsStore
import com.zx.smartring.settings.RecitationWindow
import com.zx.smartring.tasbeeh.TasbeehCloudOperation
import com.zx.smartring.tasbeeh.DailyTasbeehActivity
import com.zx.smartring.tasbeeh.TasbeehSyncCoordinator
import com.zx.smartring.tasbeeh.TasbeehSyncListener
import com.zx.smartring.tasbeeh.TasbeehSyncResult
import java.util.Locale
import java.util.IdentityHashMap
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var prayerPage: View
    private lateinit var tasbihPage: View
    private lateinit var morePage: View
    private lateinit var manualPanel: View
    private lateinit var appContent: View
    private lateinit var deviceNameValue: TextView
    private lateinit var connectButtonValue: TextView
    private lateinit var deviceTasbeehCountValue: TextView
    private lateinit var deviceProtocolStatusValue: TextView
    private lateinit var deviceResetButton: TextView
    private lateinit var bleConnectionManager: BleConnectionManager
    private lateinit var tasbeehSyncCoordinator: TasbeehSyncCoordinator
    private val bleUiHandler = Handler(Looper.getMainLooper())
    private val nearbyBleDevices = linkedMapOf<String, NearbyBleDevice>()
    private val nearbyBleRows = mutableListOf<String>()
    private var bleDeviceAdapter: ArrayAdapter<String>? = null
    private var bleScanStatus: TextView? = null
    private var bleScanDialog: AlertDialog? = null
    private var protocolReadyAnnounced = false
    private var currentPage = PAGE_TASBIH
    private var timeoutIndex = 0
    private var isRightToLeftLayout = false
    private val originalTextStates = IdentityHashMap<TextView, TextLayoutState>()
    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var magneticFieldSensor: Sensor? = null
    private lateinit var compassImage: ImageView
    private lateinit var headingValue: TextView
    private lateinit var locationManager: LocationManager
    private lateinit var currentCity: TextView
    private lateinit var currentCoordinates: TextView
    private lateinit var currentKaabaDistance: TextView
    private lateinit var profileNamePrayer: TextView
    private lateinit var profileNameMore: TextView
    private var latestDeviceCount: Int? = null
    private var sensorRegistered = false
    private var locationRegistered = false
    private var activityResumed = false
    private var directionModeActive = false
    private var hasAccelerometerReading = false
    private var hasMagneticReading = false
    private var smoothedHeading = Float.NaN
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magneticFieldReading = FloatArray(3)
    private var lastGeocodedLocation: Location? = null
    private val tasbeehSyncListener = object : TasbeehSyncListener {
        override fun onCountSynced(result: TasbeehSyncResult) {
            if (isDestroyed) return
            deviceProtocolStatusValue.setText(R.string.cloud_count_synced)
        }

        override fun onResetSynced() {
            if (isDestroyed) return
            deviceProtocolStatusValue.setText(R.string.sq666_listening)
            showMessage(R.string.device_and_cloud_reset_succeeded)
        }

        override fun onLoginRequired() {
            if (isDestroyed) return
            deviceProtocolStatusValue.setText(R.string.cloud_login_required)
        }

        override fun onSessionExpired() {
            if (isDestroyed) return
            updateProfileState()
            deviceProtocolStatusValue.setText(R.string.auth_session_expired)
            showMessage(R.string.auth_session_expired)
        }

        override fun onCloudFailure(operation: TasbeehCloudOperation) {
            if (isDestroyed) return
            val message = when (operation) {
                TasbeehCloudOperation.RESET -> R.string.cloud_reset_pending
                TasbeehCloudOperation.SYNC -> R.string.cloud_count_sync_failed
            }
            deviceProtocolStatusValue.setText(message)
            showMessage(message)
        }
    }
    private val bleConnectionListener = object : BleConnectionListener {
        override fun onConnecting(deviceName: String) {
            protocolReadyAnnounced = false
            deviceNameValue.text = getString(R.string.ble_connecting, deviceName)
            connectButtonValue.setText(R.string.ble_connecting_button)
        }

        override fun onGattConnected(deviceName: String) {
            if (isDestroyed) return
            deviceNameValue.text = deviceName
            connectButtonValue.setText(R.string.ble_disconnect)
        }

        override fun onProtocolStateChanged(state: BleConnectionState) {
            if (isDestroyed) return
            updateDeviceProtocolState(state)
        }

        override fun onProtocolReady(deviceName: String) {
            if (isDestroyed) return
            updateDeviceProtocolState(BleConnectionState.READY)
            if (!protocolReadyAnnounced) {
                protocolReadyAnnounced = true
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.ble_connected_to, deviceName),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        override fun onDeviceCount(currentCount: Int, isInitialReport: Boolean) {
            if (isDestroyed) return
            latestDeviceCount = currentCount
            deviceTasbeehCountValue.text = getString(R.string.count_format, currentCount)
            deviceProtocolStatusValue.setText(
                if (isInitialReport) {
                    R.string.sq666_initial_count_received
                } else {
                    R.string.sq666_listening
                }
            )
            tasbeehSyncCoordinator.syncDeviceCount(currentCount)
        }

        override fun onResetSucceeded() {
            if (isDestroyed) return
            latestDeviceCount = 0
            deviceTasbeehCountValue.text = getString(R.string.count_format, 0)
            deviceProtocolStatusValue.setText(R.string.cloud_reset_syncing)
            tasbeehSyncCoordinator.onDeviceResetConfirmed()
        }

        override fun onResetFailed(error: BleResetError) {
            if (isDestroyed) return
            tasbeehSyncCoordinator.onDeviceResetFailed()
            showMessage(
                when (error) {
                    BleResetError.NOT_READY -> R.string.sq666_not_ready
                    BleResetError.BUSY -> R.string.sq666_operation_busy
                    BleResetError.WRITE_FAILED -> R.string.sq666_reset_write_failed
                    BleResetError.TIMEOUT -> R.string.sq666_reset_timeout
                }
            )
        }

        override fun onProtocolError(deviceName: String, error: BleProtocolError) {
            if (isDestroyed) return
            protocolReadyAnnounced = false
            deviceNameValue.setText(R.string.device_name_disconnected)
            connectButtonValue.setText(R.string.connect)
            updateDeviceProtocolState(BleConnectionState.DISCONNECTED)
            val message = when (error) {
                BleProtocolError.MTU_NEGOTIATION_FAILED -> R.string.sq666_error_mtu
                BleProtocolError.MTU_TOO_SMALL -> R.string.sq666_error_mtu_small
                BleProtocolError.SERVICE_DISCOVERY_FAILED -> R.string.sq666_error_services
                BleProtocolError.INCOMPATIBLE_DEVICE -> R.string.sq666_error_incompatible
                BleProtocolError.NOTIFICATION_SETUP_FAILED -> R.string.sq666_error_notifications
                BleProtocolError.OPERATION_TIMEOUT -> R.string.sq666_error_timeout
            }
            showMessage(message)
        }

        override fun onDisconnected(deviceName: String?, wasReady: Boolean) {
            if (isDestroyed) return
            protocolReadyAnnounced = false
            deviceNameValue.setText(R.string.device_name_disconnected)
            connectButtonValue.setText(R.string.connect)
            updateDeviceProtocolState(BleConnectionState.DISCONNECTED)
            if (wasReady) showMessage(R.string.connect_disconnected)
        }

        override fun onConnectionFailed(deviceName: String, status: Int) {
            if (isDestroyed) return
            protocolReadyAnnounced = false
            deviceNameValue.setText(R.string.device_name_disconnected)
            connectButtonValue.setText(R.string.connect)
            updateDeviceProtocolState(BleConnectionState.DISCONNECTED)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.ble_connection_failed, deviceName, status),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    private val bleScanTimeoutRunnable = Runnable {
        bleConnectionManager.stopScan()
        val count = nearbyBleDevices.size
        bleScanStatus?.text = if (count == 0) {
            getString(R.string.ble_no_devices)
        } else {
            resources.getQuantityString(R.plurals.ble_devices_found, count, count)
        }
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateLocation(location)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            super.attachBaseContext(newBase)
            return
        }
        val languageTag = newBase.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(PREFERENCE_LANGUAGE, null)
        if (languageTag == null) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        setContentView(R.layout.activity_main)
        appContent = findViewById(R.id.app_content)
        applySystemBarInsets()

        prayerPage = findViewById(R.id.page_prayer)
        tasbihPage = findViewById(R.id.page_tasbih)
        morePage = findViewById(R.id.page_more)
        manualPanel = findViewById(R.id.manual_panel)
        compassImage = findViewById(R.id.compass_image)
        headingValue = findViewById(R.id.heading_value)
        currentCity = findViewById(R.id.current_city)
        currentCoordinates = findViewById(R.id.current_coordinates)
        currentKaabaDistance = findViewById(R.id.current_kaaba_distance)
        deviceTasbeehCountValue = findViewById(R.id.statistics_value)
        deviceProtocolStatusValue = findViewById(R.id.device_protocol_status)
        deviceResetButton = findViewById(R.id.device_reset_button)
        profileNamePrayer = findViewById(R.id.profile_name_prayer)
        profileNameMore = findViewById(R.id.profile_name_more)
        deviceNameValue = findViewById(R.id.device_name)
        connectButtonValue = findViewById(R.id.connect_button)
        tasbeehSyncCoordinator = TasbeehSyncCoordinator(this, tasbeehSyncListener)
        bleConnectionManager = BleConnectionManager(this, bleConnectionListener)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magneticFieldSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        isRightToLeftLayout = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(PREFERENCE_RTL_LAYOUT, false)
        applyLayoutDirection()

        bindNavigation()
        bindTasbihInteractions()
        bindPrayerInteractions()
        bindMoreInteractions()
        bindProfileInteractions()
        bindLanguageSwitch()
        selectPage(PAGE_TASBIH)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!handleBack()) finish()
            }
        }
    }

    private fun configureSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val statusBarScrim = findViewById<View>(R.id.status_bar_scrim)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            appContent.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            statusBarScrim.layoutParams = statusBarScrim.layoutParams.apply {
                height = insets.top
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bindNavigation() {
        findViewById<View>(R.id.nav_prayer).setOnClickListener { selectPage(PAGE_PRAYER) }
        findViewById<View>(R.id.nav_tasbih).setOnClickListener { selectPage(PAGE_TASBIH) }
        findViewById<View>(R.id.nav_more).setOnClickListener { selectPage(PAGE_MORE) }
    }

    private fun handleBleButtonClick() {
        if (bleConnectionManager.connectionState == BleConnectionState.DISCONNECTED) {
            ensureBleReadyAndScan()
        } else {
            connectButtonValue.setText(R.string.ble_connecting_button)
            showMessage(R.string.ble_disconnecting)
            bleConnectionManager.disconnect()
        }
    }

    private fun updateDeviceProtocolState(state: BleConnectionState) {
        deviceProtocolStatusValue.setText(
            when (state) {
                BleConnectionState.DISCONNECTED -> R.string.sq666_disconnected
                BleConnectionState.CONNECTING -> R.string.sq666_connecting
                BleConnectionState.MTU_NEGOTIATING -> R.string.sq666_mtu_negotiating
                BleConnectionState.DISCOVERING_SERVICES -> R.string.sq666_discovering_services
                BleConnectionState.ENABLING_NOTIFICATIONS -> R.string.sq666_enabling_notifications
                BleConnectionState.WAITING_INITIAL_COUNT -> R.string.sq666_waiting_initial_count
                BleConnectionState.READY -> R.string.sq666_listening
            }
        )
    }

    private fun confirmDeviceCountReset() {
        if (bleConnectionManager.connectionState != BleConnectionState.READY) {
            showMessage(R.string.sq666_not_ready)
            return
        }
        if (SessionStore.get(this) == null) {
            showMessage(R.string.cloud_reset_login_required)
            openAccount()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sq666_reset_confirm_title)
            .setMessage(R.string.sq666_reset_confirm_message)
            .setNegativeButton(R.string.ble_cancel, null)
            .setPositiveButton(R.string.sq666_reset_button) { _, _ ->
                deviceProtocolStatusValue.setText(R.string.sq666_resetting)
                tasbeehSyncCoordinator.onDeviceResetStarted()
                if (!bleConnectionManager.resetDeviceCount()) {
                    tasbeehSyncCoordinator.onDeviceResetFailed()
                    updateDeviceProtocolState(bleConnectionManager.connectionState)
                }
            }
            .show()
    }

    private fun ensureBleReadyAndScan() {
        if (!bleConnectionManager.isSupported()) {
            showMessage(R.string.ble_scan_unavailable)
            return
        }
        if (!hasBlePermissions()) {
            requestBlePermissions()
            return
        }
        if (!bleConnectionManager.isEnabled()) {
            requestBluetoothEnable()
            return
        }
        showBleDeviceDialog()
    }

    private fun hasBlePermissions(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBlePermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissions(permissions, BLE_PERMISSION_REQUEST)
    }

    @Suppress("DEPRECATION")
    private fun requestBluetoothEnable() {
        if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBlePermissions()
            return
        }
        try {
            startActivityForResult(
                Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                BLUETOOTH_ENABLE_REQUEST
            )
        } catch (_: SecurityException) {
            showMessage(R.string.ble_enable_required)
        }
    }

    private fun showBleDeviceDialog() {
        if (bleScanDialog?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.dialog_ble_devices, null)
        bleScanStatus = content.findViewById(R.id.ble_scan_status)
        val deviceList = content.findViewById<ListView>(R.id.ble_device_list)
        bleDeviceAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            nearbyBleRows
        )
        deviceList.adapter = bleDeviceAdapter
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ble_dialog_title)
            .setView(content)
            .setNegativeButton(R.string.ble_cancel, null)
            .setNeutralButton(R.string.ble_refresh, null)
            .create()
        bleScanDialog = dialog
        deviceList.setOnItemClickListener { _, _, position, _ ->
            val selectedDevice = nearbyBleDevices.values.elementAtOrNull(position)
                ?: return@setOnItemClickListener
            stopBleScan()
            dialog.dismiss()
            bleConnectionManager.connect(selectedDevice)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                startBleScan()
            }
        }
        dialog.setOnDismissListener {
            stopBleScan()
            bleScanDialog = null
            bleScanStatus = null
            bleDeviceAdapter = null
        }
        dialog.show()
        startBleScan()
    }

    private fun startBleScan() {
        nearbyBleDevices.clear()
        nearbyBleRows.clear()
        bleDeviceAdapter?.notifyDataSetChanged()
        bleScanStatus?.setText(R.string.ble_scanning)
        bleUiHandler.removeCallbacks(bleScanTimeoutRunnable)
        val started = bleConnectionManager.startScan(
            getString(R.string.ble_unnamed_device),
            onDeviceFound = { device ->
                runOnUiThread { addOrUpdateBleDevice(device) }
            },
            onScanFailed = { errorCode ->
                runOnUiThread {
                    bleUiHandler.removeCallbacks(bleScanTimeoutRunnable)
                    bleScanStatus?.text = getString(R.string.ble_scan_failed, errorCode)
                }
            }
        )
        if (!started) {
            bleScanStatus?.setText(R.string.ble_scan_unavailable)
            return
        }
        bleUiHandler.postDelayed(bleScanTimeoutRunnable, BLE_SCAN_DURATION_MS)
    }

    private fun addOrUpdateBleDevice(device: NearbyBleDevice) {
        if (bleScanDialog?.isShowing != true) return
        nearbyBleDevices[device.address] = device
        val sortedDevices = nearbyBleDevices.values.sortedWith(
            compareByDescending<NearbyBleDevice> { it.isSq666Candidate }
                .thenByDescending { it.rssi }
        )
        nearbyBleDevices.clear()
        sortedDevices.forEach { nearbyBleDevices[it.address] = it }
        nearbyBleRows.clear()
        nearbyBleRows += nearbyBleDevices.values.map {
            getString(
                if (it.isSq666Candidate) {
                    R.string.ble_device_row_sq666
                } else {
                    R.string.ble_device_row
                },
                it.name,
                it.address,
                it.rssi
            )
        }
        bleDeviceAdapter?.notifyDataSetChanged()
        bleScanStatus?.text = resources.getQuantityString(
            R.plurals.ble_devices_found,
            nearbyBleDevices.size,
            nearbyBleDevices.size
        )
    }

    private fun stopBleScan() {
        bleUiHandler.removeCallbacks(bleScanTimeoutRunnable)
        if (::bleConnectionManager.isInitialized) bleConnectionManager.stopScan()
    }

    private fun bindTasbihInteractions() {
        connectButtonValue.setOnClickListener { handleBleButtonClick() }
        deviceResetButton.setOnClickListener { confirmDeviceCountReset() }
        updateDeviceProtocolState(BleConnectionState.DISCONNECTED)
        findViewById<View>(R.id.card_daily_calendar).setOnClickListener {
            if (SessionStore.get(this) == null) {
                showMessage(R.string.daily_calendar_login_required)
                openAccount()
            } else {
                startActivity(Intent(this, DailyTasbeehActivity::class.java))
            }
        }

        val layoutDirectionValue = findViewById<TextView>(R.id.layout_direction_value)
        updateLayoutDirectionValue(layoutDirectionValue)
        findViewById<View>(R.id.card_rotate).setOnClickListener {
            isRightToLeftLayout = !isRightToLeftLayout
            getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREFERENCE_RTL_LAYOUT, isRightToLeftLayout)
                .apply()
            applyLayoutDirection()
            updateLayoutDirectionValue(layoutDirectionValue)
            showMessage(
                if (isRightToLeftLayout) R.string.layout_changed_rtl
                else R.string.layout_changed_ltr
            )
        }

        val savedSettings = AppSettingsStore.get(this)
        val reminderStatus = findViewById<TextView>(R.id.reminder_status)
        val reminderSwitch = findViewById<Switch>(R.id.reminder_switch)
        reminderSwitch.isChecked = savedSettings.dailyReminderEnabled
        reminderStatus.setText(
            if (savedSettings.dailyReminderEnabled) R.string.enabled else R.string.disabled
        )
        reminderSwitch.setOnCheckedChangeListener { _, checked ->
            reminderStatus.setText(if (checked) R.string.enabled else R.string.disabled)
            AppSettingsStore.setDailyReminderEnabled(this, checked)
            if (checked) {
                if (AppSettingsStore.get(this).recitationWindow == null) {
                    showRecitationTimePicker {
                        AppSettingsStore.setDailyReminderEnabled(this, false)
                        reminderSwitch.isChecked = false
                    }
                } else {
                    requestNotificationPermissionIfNeeded()
                    RecitationReminderScheduler.sync(this)
                }
            } else {
                RecitationReminderScheduler.sync(this)
            }
        }

        val recitationTime = findViewById<TextView>(R.id.recitation_time)
        updateRecitationWindowValue(recitationTime, savedSettings.recitationWindow)
        findViewById<View>(R.id.card_recitation).setOnClickListener {
            showRecitationTimePicker()
        }
        findViewById<View>(R.id.card_recitation).setOnLongClickListener {
            AppSettingsStore.setRecitationWindow(this, null)
            AppSettingsStore.setDailyReminderEnabled(this, false)
            reminderSwitch.isChecked = false
            updateRecitationWindowValue(recitationTime, null)
            RecitationReminderScheduler.sync(this)
            showMessage(R.string.recitation_time_cleared)
            true
        }

        val timeout = findViewById<TextView>(R.id.timeout_value)
        val timeoutValues =
            intArrayOf(R.string.timeout_10s, R.string.timeout_20s, R.string.timeout_30s)
        timeoutIndex = AppSettingsStore.SCREEN_TIMEOUT_OPTIONS
            .indexOf(savedSettings.screenTimeoutSeconds)
            .coerceAtLeast(0)
        timeout.setText(timeoutValues[timeoutIndex])
        findViewById<View>(R.id.card_timeout).setOnClickListener {
            timeoutIndex = (timeoutIndex + 1) % timeoutValues.size
            timeout.setText(timeoutValues[timeoutIndex])
            AppSettingsStore.setScreenTimeoutSeconds(
                this,
                AppSettingsStore.SCREEN_TIMEOUT_OPTIONS[timeoutIndex]
            )
            showMessage(R.string.screen_timeout_updated)
        }
    }

    private fun showRecitationTimePicker(onCancelled: (() -> Unit)? = null) {
        val currentWindow = AppSettingsStore.get(this).recitationWindow
        val initialStart = currentWindow?.startMinuteOfDay ?: DEFAULT_RECITATION_START
        val initialEnd = currentWindow?.endMinuteOfDay ?: DEFAULT_RECITATION_END
        val use24HourClock = android.text.format.DateFormat.is24HourFormat(this)
        TimePickerDialog(
            this,
            { _, startHour, startMinute ->
                TimePickerDialog(
                    this,
                    endTimeSelected@{ _, endHour, endMinute ->
                        val start = startHour * 60 + startMinute
                        val end = endHour * 60 + endMinute
                        if (start == end) {
                            showMessage(R.string.recitation_time_invalid)
                            return@endTimeSelected
                        }
                        val window = RecitationWindow(start, end)
                        AppSettingsStore.setRecitationWindow(this, window)
                        updateRecitationWindowValue(
                            findViewById(R.id.recitation_time),
                            window
                        )
                        showMessage(R.string.recitation_time_updated)
                        if (AppSettingsStore.get(this).dailyReminderEnabled) {
                            requestNotificationPermissionIfNeeded()
                            RecitationReminderScheduler.sync(this)
                        }
                    },
                    initialEnd / MINUTES_PER_HOUR,
                    initialEnd % MINUTES_PER_HOUR,
                    use24HourClock
                ).apply {
                    setTitle(R.string.recitation_end_time)
                    setOnCancelListener { onCancelled?.invoke() }
                    show()
                }
            },
            initialStart / MINUTES_PER_HOUR,
            initialStart % MINUTES_PER_HOUR,
            use24HourClock
        ).apply {
            setTitle(R.string.recitation_start_time)
            setOnCancelListener { onCancelled?.invoke() }
            show()
        }
    }

    private fun updateRecitationWindowValue(value: TextView, window: RecitationWindow?) {
        if (window == null) {
            value.setText(R.string.time_unset)
            return
        }
        value.text = getString(
            R.string.recitation_time_format,
            window.startMinuteOfDay / MINUTES_PER_HOUR,
            window.startMinuteOfDay % MINUTES_PER_HOUR,
            window.endMinuteOfDay / MINUTES_PER_HOUR,
            window.endMinuteOfDay % MINUTES_PER_HOUR
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
        }
    }

    private fun bindPrayerInteractions() {
        val prayerButton = findViewById<TextView>(R.id.prayer_mode_button)
        val directionButton = findViewById<TextView>(R.id.direction_mode_button)
        val prayerContent = findViewById<View>(R.id.prayer_times_content)
        val directionContent = findViewById<View>(R.id.direction_content)

        prayerButton.setOnClickListener {
            directionModeActive = false
            stopCompass()
            stopLocationUpdates()
            prayerContent.visibility = View.VISIBLE
            directionContent.visibility = View.GONE
            setModeButtonState(prayerButton, true)
            setModeButtonState(directionButton, false)
        }
        directionButton.setOnClickListener {
            directionModeActive = true
            prayerContent.visibility = View.GONE
            directionContent.visibility = View.VISIBLE
            setModeButtonState(prayerButton, false)
            setModeButtonState(directionButton, true)
            startCompass()
            ensureLocationUpdates()
        }
        compassImage.setOnClickListener { openManual() }
        findViewById<View>(R.id.location_card).setOnClickListener { ensureLocationUpdates() }

        val prayerRows = intArrayOf(
            R.id.row_fajr,
            R.id.row_sunrise,
            R.id.row_dhuhr,
            R.id.row_asr,
            R.id.row_maghrib,
            R.id.row_isha
        )
        prayerRows.forEach { rowId ->
            findViewById<View>(rowId).setOnClickListener { selected ->
                prayerRows.forEach { findViewById<View>(it).setBackgroundColor(Color.TRANSPARENT) }
                selected.setBackgroundResource(R.drawable.bg_selected_row)
            }
        }
    }

    private fun bindMoreInteractions() {
        findViewById<View>(R.id.manual_entry).setOnClickListener { openManual() }
        findViewById<View>(R.id.about_entry).setOnClickListener {
            showMessage(R.string.about_message)
        }
        findViewById<View>(R.id.manual_back).setOnClickListener { closeManual() }
    }

    private fun bindProfileInteractions() {
        findViewById<View>(R.id.profile_avatar_prayer).setOnClickListener { openAccount() }
        findViewById<View>(R.id.profile_avatar_more).setOnClickListener { openAccount() }
    }

    private fun openAccount() {
        startActivity(Intent(this, LoginActivity::class.java))
    }

    private fun updateProfileState() {
        val session = SessionStore.get(this)
        val displayName = session?.name ?: getString(R.string.profile_guest)
        profileNamePrayer.text = displayName
        profileNameMore.text = displayName
    }

    private fun bindLanguageSwitch() {
        findViewById<View>(R.id.language_switch).setOnClickListener { switchLanguage() }
    }

    private fun switchLanguage() {
        val currentLanguage = resources.configuration.locales[0].language
        val targetLanguage = if (currentLanguage == Locale.ENGLISH.language) {
            LANGUAGE_CHINESE
        } else {
            LANGUAGE_ENGLISH
        }
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREFERENCE_LANGUAGE, targetLanguage)
            .apply()

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(targetLanguage)
        } else {
            recreate()
        }
    }

    private fun applyLayoutDirection() {
        appContent.layoutDirection = if (isRightToLeftLayout) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
        applyTextDirectionRecursively(appContent, isRightToLeftLayout)
    }

    private fun applyTextDirectionRecursively(view: View, rightToLeft: Boolean) {
        if (view is TextView) {
            val original = originalTextStates.getOrPut(view) {
                TextLayoutState(view.gravity, view.textDirection, view.textAlignment)
            }
            if (rightToLeft) {
                view.textDirection = View.TEXT_DIRECTION_RTL
                view.textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                val horizontalGravity = original.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
                if (horizontalGravity != Gravity.CENTER_HORIZONTAL) {
                    val nonHorizontalGravity =
                        original.gravity and Gravity.HORIZONTAL_GRAVITY_MASK.inv()
                    view.gravity = nonHorizontalGravity or Gravity.END
                }
            } else {
                view.gravity = original.gravity
                view.textDirection = original.textDirection
                view.textAlignment = original.textAlignment
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyTextDirectionRecursively(view.getChildAt(index), rightToLeft)
            }
        }
    }

    private fun updateLayoutDirectionValue(valueView: TextView) {
        valueView.setText(if (isRightToLeftLayout) R.string.layout_rtl else R.string.layout_ltr)
    }

    private fun selectPage(page: Int) {
        currentPage = page
        prayerPage.visibility = if (page == PAGE_PRAYER) View.VISIBLE else View.GONE
        tasbihPage.visibility = if (page == PAGE_TASBIH) View.VISIBLE else View.GONE
        morePage.visibility = if (page == PAGE_MORE) View.VISIBLE else View.GONE

        val activeColor = ContextCompat.getColor(this, R.color.teal)
        val inactiveColor = ContextCompat.getColor(this, R.color.nav_inactive)
        val navigationIds = arrayOf(
            intArrayOf(R.id.nav_prayer_icon, R.id.nav_prayer_label),
            intArrayOf(R.id.nav_tasbih_icon, R.id.nav_tasbih_label),
            intArrayOf(R.id.nav_more_icon, R.id.nav_more_label)
        )
        navigationIds.forEachIndexed { index, ids ->
            val color = if (index == page) activeColor else inactiveColor
            ids.forEach { findViewById<TextView>(it).setTextColor(color) }
        }

        if (page == PAGE_TASBIH) {
            findViewById<ScrollView>(R.id.tasbih_scroll).isSmoothScrollingEnabled = true
        }
        if (page == PAGE_PRAYER && directionModeActive) {
            startCompass()
            if (hasLocationPermission()) startLocationUpdates()
        } else {
            stopCompass()
            stopLocationUpdates()
        }
    }

    private fun setModeButtonState(button: TextView, selected: Boolean) {
        button.setBackgroundResource(if (selected) R.drawable.bg_pill_active else R.drawable.bg_pill_inactive)
        button.setTextColor(
            ContextCompat.getColor(this, if (selected) R.color.white else R.color.pill_active)
        )
    }

    private fun openManual() {
        stopCompass()
        stopLocationUpdates()
        manualPanel.visibility = View.VISIBLE
        manualPanel.post {
            manualPanel.translationX = manualPanel.width.toFloat()
            manualPanel.alpha = 0.92f
            manualPanel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(320)
                .start()
        }
    }

    private fun closeManual() {
        manualPanel.animate()
            .translationX(manualPanel.width.toFloat())
            .alpha(0.92f)
            .setDuration(260)
            .withEndAction {
                manualPanel.visibility = View.GONE
                manualPanel.translationX = 0f
                manualPanel.alpha = 1f
                if (currentPage == PAGE_PRAYER && directionModeActive) {
                    startCompass()
                    if (hasLocationPermission()) startLocationUpdates()
                }
            }
            .start()
    }

    private fun showMessage(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureLocationUpdates() {
        if (!hasLocationPermission()) {
            currentCity.setText(R.string.location_permission_required)
            currentCoordinates.setText(R.string.coordinates_waiting)
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        currentCity.setText(R.string.location_waiting)
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (!activityResumed || locationRegistered || !directionModeActive || !hasLocationPermission()) return
        val availableProviders = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
        }
        if (availableProviders.isEmpty()) {
            currentCity.setText(R.string.location_unavailable)
            return
        }

        var newestLocation: Location? = null
        try {
            availableProviders.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    LOCATION_UPDATE_INTERVAL_MS,
                    LOCATION_UPDATE_MIN_DISTANCE_METERS,
                    locationListener
                )
                val lastLocation = locationManager.getLastKnownLocation(provider)
                if (lastLocation != null && (newestLocation == null || lastLocation.time > newestLocation!!.time)) {
                    newestLocation = lastLocation
                }
            }
            locationRegistered = true
            newestLocation?.let(::updateLocation)
        } catch (_: SecurityException) {
            locationRegistered = false
            currentCity.setText(R.string.location_permission_denied)
        }
    }

    private fun stopLocationUpdates() {
        if (locationRegistered) {
            runCatching { locationManager.removeUpdates(locationListener) }
        }
        locationRegistered = false
    }

    private fun updateLocation(location: Location) {
        currentCoordinates.text = getString(
            R.string.coordinates_format,
            location.latitude,
            location.longitude
        )
        val distanceResult = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            KAABA_LATITUDE,
            KAABA_LONGITUDE,
            distanceResult
        )
        currentKaabaDistance.text = getString(
            R.string.distance_to_kaaba_format,
            distanceResult[0] / METERS_PER_KILOMETER
        )

        val previousGeocode = lastGeocodedLocation
        if (previousGeocode == null || previousGeocode.distanceTo(location) >= GEOCODE_MIN_DISTANCE_METERS) {
            lastGeocodedLocation = Location(location)
            resolveCity(location)
        }
    }

    private fun resolveCity(location: Location) {
        if (!Geocoder.isPresent()) {
            currentCity.setText(R.string.city_unknown)
            return
        }
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val geocoder = Geocoder(this, locale)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(
                location.latitude,
                location.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        runOnUiThread { showResolvedCity(addresses.firstOrNull()) }
                    }

                    override fun onError(errorMessage: String?) {
                        runOnUiThread { currentCity.setText(R.string.city_unknown) }
                    }
                }
            )
        } else {
            Thread {
                @Suppress("DEPRECATION")
                val address = runCatching {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()
                }.getOrNull()
                runOnUiThread { showResolvedCity(address) }
            }.start()
        }
    }

    private fun showResolvedCity(address: Address?) {
        val city = address?.locality
            ?: address?.subAdminArea
            ?: address?.adminArea
            ?: address?.countryName
        currentCity.text = city?.takeIf { it.isNotBlank() } ?: getString(R.string.city_unknown)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            LOCATION_PERMISSION_REQUEST -> {
                if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                    startLocationUpdates()
                } else {
                    currentCity.setText(R.string.location_permission_denied)
                    currentCoordinates.setText(R.string.coordinates_waiting)
                }
            }

            NOTIFICATION_PERMISSION_REQUEST -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    RecitationReminderScheduler.sync(this)
                } else {
                    showMessage(R.string.notification_permission_denied)
                }
            }

            BLE_PERMISSION_REQUEST -> {
                if (hasBlePermissions()) {
                    ensureBleReadyAndScan()
                } else {
                    showMessage(R.string.ble_permission_denied)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != BLUETOOTH_ENABLE_REQUEST) return
        if (resultCode == RESULT_OK && hasBlePermissions() && bleConnectionManager.isEnabled()) {
            showBleDeviceDialog()
        } else {
            showMessage(R.string.ble_enable_required)
        }
    }

    private fun startCompass() {
        if (!activityResumed || sensorRegistered || !directionModeActive) return
        headingValue.setText(R.string.compass_calibrating)
        smoothedHeading = Float.NaN
        sensorRegistered = when {
            rotationVectorSensor != null -> sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_UI
            )

            accelerometerSensor != null && magneticFieldSensor != null -> {
                val accelerometerRegistered = sensorManager.registerListener(
                    this,
                    accelerometerSensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                val magneticRegistered = sensorManager.registerListener(
                    this,
                    magneticFieldSensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                accelerometerRegistered && magneticRegistered
            }

            else -> false
        }
        if (!sensorRegistered) headingValue.setText(R.string.compass_unavailable)
    }

    private fun stopCompass() {
        if (sensorRegistered) sensorManager.unregisterListener(this)
        sensorRegistered = false
        hasAccelerometerReading = false
        hasMagneticReading = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val matrixReady = when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                true
            }

            Sensor.TYPE_ACCELEROMETER -> {
                copySensorValues(event.values, accelerometerReading)
                hasAccelerometerReading = true
                hasMagneticReading && SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    accelerometerReading,
                    magneticFieldReading
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                copySensorValues(event.values, magneticFieldReading)
                hasMagneticReading = true
                hasAccelerometerReading && SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    accelerometerReading,
                    magneticFieldReading
                )
            }

            else -> false
        }
        if (!matrixReady) return

        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val rawHeading = ((Math.toDegrees(orientationAngles[0].toDouble()).toFloat() + 360f) % 360f)
        smoothedHeading = if (smoothedHeading.isNaN()) {
            rawHeading
        } else {
            val shortestDelta = ((rawHeading - smoothedHeading + 540f) % 360f) - 180f
            (smoothedHeading + shortestDelta * HEADING_SMOOTHING + 360f) % 360f
        }
        updateCompass(smoothedHeading)
    }

    private fun copySensorValues(source: FloatArray, destination: FloatArray) {
        for (index in destination.indices) destination[index] = source[index]
    }

    private fun updateCompass(heading: Float) {
        compassImage.rotation = -heading
        val roundedHeading = heading.roundToInt() % 360
        val directionIndex = (((heading + 22.5f) / 45f).toInt()) % DIRECTION_STRINGS.size
        headingValue.text = getString(
            R.string.direction_reading,
            getString(DIRECTION_STRINGS[directionIndex]),
            roundedHeading
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onResume() {
        super.onResume()
        activityResumed = true
        updateProfileState()
        if (SessionStore.get(this) != null) {
            latestDeviceCount?.let(tasbeehSyncCoordinator::syncDeviceCount)
        }
        if (currentPage == PAGE_PRAYER && directionModeActive) {
            startCompass()
            if (hasLocationPermission()) startLocationUpdates()
        }
    }

    override fun onPause() {
        activityResumed = false
        bleScanDialog?.dismiss()
        stopCompass()
        stopLocationUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        bleUiHandler.removeCallbacks(bleScanTimeoutRunnable)
        if (::bleConnectionManager.isInitialized) bleConnectionManager.close()
        if (::tasbeehSyncCoordinator.isInitialized) tasbeehSyncCoordinator.close()
        super.onDestroy()
    }

    private fun handleBack(): Boolean {
        if (manualPanel.visibility == View.VISIBLE) {
            closeManual()
            return true
        }
        if (currentPage != PAGE_TASBIH) {
            selectPage(PAGE_TASBIH)
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (android.os.Build.VERSION.SDK_INT < 33 && !handleBack()) {
            super.onBackPressed()
        }
    }

    private companion object {
        const val PAGE_PRAYER = 0
        const val PAGE_TASBIH = 1
        const val PAGE_MORE = 2
        const val HEADING_SMOOTHING = 0.18f
        const val LOCATION_PERMISSION_REQUEST = 2001
        const val NOTIFICATION_PERMISSION_REQUEST = 2002
        const val BLE_PERMISSION_REQUEST = 2003
        const val BLUETOOTH_ENABLE_REQUEST = 2004
        const val BLE_SCAN_DURATION_MS = 12_000L
        const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
        const val LOCATION_UPDATE_MIN_DISTANCE_METERS = 1f
        const val GEOCODE_MIN_DISTANCE_METERS = 5_000f
        const val KAABA_LATITUDE = 21.4225
        const val KAABA_LONGITUDE = 39.8262
        const val METERS_PER_KILOMETER = 1_000f
        const val PREFERENCES_NAME = "smart_ring_preferences"
        const val PREFERENCE_LANGUAGE = "app_language"
        const val PREFERENCE_RTL_LAYOUT = "rtl_layout"
        const val LANGUAGE_CHINESE = "zh-CN"
        const val LANGUAGE_ENGLISH = "en"
        const val MINUTES_PER_HOUR = 60
        const val DEFAULT_RECITATION_START = 5 * MINUTES_PER_HOUR + 30
        const val DEFAULT_RECITATION_END = 6 * MINUTES_PER_HOUR
        val DIRECTION_STRINGS = intArrayOf(
            R.string.direction_north,
            R.string.direction_northeast,
            R.string.direction_east,
            R.string.direction_southeast,
            R.string.direction_south,
            R.string.direction_southwest,
            R.string.direction_west,
            R.string.direction_northwest
        )
    }

    private data class TextLayoutState(
        val gravity: Int,
        val textDirection: Int,
        val textAlignment: Int
    )
}
