package be.hokkaydo.presenter.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import be.hokkaydo.presenter.BLEManager
import be.hokkaydo.presenter.ForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// --- Data class for UI State ---
data class MainUiState(
    val bluetoothEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val requiredPermissionsGranted: Boolean = false,
    val neverScanned: Boolean = true, // if at least one scan has been made
    val isScanning: Boolean = false,
    val discoveredDevices: List<BluetoothDeviceWrapper> = emptyList(),
    val showEnableBluetoothDialog: Boolean = false,
    val showEnableLocationDialog: Boolean = false,
    val permissionsChecked: Boolean = false, // To avoid re-asking immediately
    val connectedDevice: BluetoothDeviceWrapper? = null
)

// Wrapper for BluetoothDevice to make it easier to display (e.g., name)
data class BluetoothDeviceWrapper(
    val device: BluetoothDevice,
)

class MainViewModel internal constructor(
    private val applicationContext: Context, // For system services
    private val bluetoothAdapter: BluetoothAdapter?,
    private val bleManager: BLEManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    var foregroundServiceIntent: Intent? = null

    // --- Permissions ---
    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION // Still needed for discovery on many devices
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH, // Includes BLUETOOTH_ADMIN for older versions
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    init {
        checkInitialStates()
    }

    fun checkInitialStates() {
        _uiState.update {
            it.copy(
                bluetoothEnabled = isBluetoothEnabled(),
                locationEnabled = isLocationEnabled(applicationContext),
                requiredPermissionsGranted = arePermissionsGranted(applicationContext)
            )
        }
    }


    fun onPermissionsResult(grantedPermissions: Map<String, Boolean>) {
        val allGranted = requiredPermissions.all { grantedPermissions.getOrDefault(it, false) }
        _uiState.update {
            it.copy(
                requiredPermissionsGranted = allGranted,
                permissionsChecked = true
            )
        }
        // If not all granted, you might want to show a message or guide the user.
    }

    fun onBluetoothStateChanged(enabled: Boolean) {
        _uiState.update { it.copy(bluetoothEnabled = enabled, showEnableBluetoothDialog = false) }
    }

    fun onLocationStateChanged(enabled: Boolean) {
        _uiState.update { it.copy(locationEnabled = enabled, showEnableLocationDialog = false) }
    }

    fun requestEnableBluetooth() {
        _uiState.update { it.copy(showEnableBluetoothDialog = true) }
    }

    fun requestEnableLocation() {
        _uiState.update { it.copy(showEnableLocationDialog = true) }
    }

    fun dismissEnableBluetoothDialog() {
        _uiState.update { it.copy(showEnableBluetoothDialog = false) }
    }

    fun dismissEnableLocationDialog() {
        _uiState.update { it.copy(showEnableLocationDialog = false) }
    }


    // --- Bluetooth Scanning ---
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScanning() {
        if (!areAllPrerequisitesMet()) {
            // Optionally, show a message that prerequisites are not met
            Log.w("MainViewModel", "Prerequisites not met for scanning.")
            // Re-check and update UI to prompt user if something is off
            checkInitialStates()
            return
        }

        _uiState.update { it.copy(isScanning = true, discoveredDevices = emptyList(), neverScanned = false) } // Clear previous devices
        Log.d("MainViewModel", "Starting BLE Scan...")

        bleManager.startScan {device ->
            onDeviceFound(device)
        }
    }

    // This method would be called by your actual BLE scanning mechanism
    // Ensure you have BLUETOOTH_CONNECT permission before accessing device.name or device.address
    private fun onDeviceFound(device: BluetoothDevice) {
        try {
            if (device.name == null) return
        } catch (e: SecurityException) { return }

        val wrapper = BluetoothDeviceWrapper(device)
        _uiState.update {
            // Add device only if not already present based on address
            if (it.discoveredDevices.none { devWrapper -> devWrapper.device.address == device.address }) {
                it.copy(discoveredDevices = it.discoveredDevices + wrapper)
            } else {
                it // No change if device already exists
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() {
        if (_uiState.value.isScanning) {
            _uiState.update { it.copy(isScanning = false) }
            Log.d("MainViewModel", "Stopping BLE Scan.")
            bleManager.stopScan()
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun onDeviceSelected(deviceWrapper: BluetoothDeviceWrapper) {
        bleManager.connectToDevice(deviceWrapper.device) {
            Log.d("MainViewModel", "Connected to ${deviceWrapper.device.name}")
            _uiState.update { it.copy(connectedDevice = deviceWrapper) }
        }
        stopScanning()
        foregroundServiceIntent = Intent(ForegroundService.ACTION_FOREGROUND_WAKELOCK).setClass(applicationContext, ForegroundService::class.java)
        applicationContext.startService(foregroundServiceIntent)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        bleManager.disconnect()
        _uiState.update { it.copy(connectedDevice = null) }
        applicationContext.stopService(foregroundServiceIntent)
    }

    // --- Utility Functions ---
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun isLocationEnabled(context: Context): Boolean {
        // Location is critical for BLE scans on Android 6.0+
        // and often good practice to check for all versions.
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun arePermissionsGranted(context: Context): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun areAllPrerequisitesMet(): Boolean {
        return _uiState.value.bluetoothEnabled &&
                _uiState.value.locationEnabled &&
                _uiState.value.requiredPermissionsGranted
    }

    // --- Called when the ViewModel is cleared ---
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onCleared() {
        super.onCleared()
        stopScanning() // Ensure scanning is stopped if ViewModel is destroyed
        bleManager.disconnect()
        applicationContext.stopService(foregroundServiceIntent)
    }
}