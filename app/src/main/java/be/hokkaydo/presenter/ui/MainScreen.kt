package be.hokkaydo.presenter.ui // Or your UI package

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import be.hokkaydo.presenter.BLEManager
import com.google.accompanist.permissions.*

@RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class) // For Accompanist Permissions
@Composable
fun MainScreen(
    viewModelFactory: @Composable () -> MainViewModel = {
        // Default factory, can be customized for previews or dependency injection
        val context = LocalContext.current.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val bleManager = BLEManager.init(context, bluetoothAdapter)
        viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(context, bluetoothAdapter, bleManager) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        })
    }
) {
    val viewModel: MainViewModel = viewModelFactory()
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // --- Permission Handling ---
    val permissionsState = rememberMultiplePermissionsState(
        permissions = viewModel.requiredPermissions
    ) { grantedPermissions ->
        viewModel.onPermissionsResult(grantedPermissions)
    }

    // --- Activity Result Launchers for enabling Bluetooth/Location ---
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result -> */ // Result can be checked if needed
        viewModel.onBluetoothStateChanged(viewModel.isBluetoothEnabled())
    }

    val enableLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* result -> */
        viewModel.onLocationStateChanged(viewModel.isLocationEnabled(context))
    }

    // --- Effect to check initial states and request permissions if needed ---
    LaunchedEffect(key1 = permissionsState.allPermissionsGranted) {
        if (!permissionsState.allPermissionsGranted && !uiState.permissionsChecked) {
            // Only launch if permissions not granted AND we haven't checked them yet in this session
            // or if the permissions were previously denied and we want to guide user.
            if (permissionsState.shouldShowRationale || !permissionsState.permissions.isNotEmpty()) {
                permissionsState.launchMultiplePermissionRequest()
            }
            // If rationale should be shown, or if never requested, request them.
        } else if (permissionsState.allPermissionsGranted) {
            // If permissions are granted, ensure the ViewModel knows.
            // This handles cases where permissions were granted externally (e.g., in settings)
            if(!uiState.requiredPermissionsGranted) {
                viewModel.onPermissionsResult(viewModel.requiredPermissions.associateWith { true })
            }
        }
        // Initial check in case states changed outside the app
        viewModel.checkInitialStates()
    }

    // --- Dialogs ---
    if (uiState.showEnableBluetoothDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissEnableBluetoothDialog() },
            title = { Text("Enable Bluetooth") },
            text = { Text("Bluetooth is required to scan for nearby devices. Please enable it.") },
            confirmButton = {
                Button(onClick = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBluetoothLauncher.launch(enableBtIntent)
                    viewModel.dismissEnableBluetoothDialog()
                }) { Text("Enable") }
            },
            dismissButton = {
                Button(onClick = { viewModel.dismissEnableBluetoothDialog() }) { Text("Cancel") }
            }
        )
    }

    if (uiState.showEnableLocationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissEnableLocationDialog() },
            title = { Text("Enable Location") },
            text = { Text("Location services are required for Bluetooth scanning of neary devices.") },
            confirmButton = {
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    enableLocationLauncher.launch(intent)
                    viewModel.dismissEnableLocationDialog()
                }) { Text("Settings") }
            },
            dismissButton = {
                Button(onClick = { viewModel.dismissEnableLocationDialog() }) { Text("Cancel") }
            }
        )
    }


    // --- Main UI Layout ---
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Presenter") })
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if(uiState.neverScanned || !viewModel.areAllPrerequisitesMet()) {
                GreetingAndPrerequisites(
                    uiState = uiState,
                    onEnableBluetoothClick = { viewModel.requestEnableBluetooth() },
                    onEnableLocationClick = { viewModel.requestEnableLocation() },
                    onRequestPermissionsClick = {
                        if (permissionsState.shouldShowRationale) {
                            // Show a rationale dialog before re-requesting, or direct to settings
                            // For simplicity, just re-requesting here.
                            // In a real app, provide more context if rationale is true.
                            permissionsState.launchMultiplePermissionRequest()
                        } else if (!permissionsState.allPermissionsGranted && permissionsState.permissions.isNotEmpty()) {
                            // Permissions were denied and no rationale should be shown,
                            // guide user to app settings.
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            context.startActivity(intent)
                        } else {
                            permissionsState.launchMultiplePermissionRequest()
                        }
                    }
                )
            } else {
                if (uiState.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                    Text("Scanning for devices...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (viewModel.areAllPrerequisitesMet()) {
                Button(
                    onClick = {
                        if (uiState.isScanning) viewModel.stopScanning() else viewModel.startScanning()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.isScanning) "Stop Scanning" else "Start Bluetooth Scan")
                }
                if (uiState.connectedDevice == null) {
                    DeviceList(devices = uiState.discoveredDevices, onDeviceClick = { deviceWrapper -> viewModel.onDeviceSelected(deviceWrapper) })
                } else {
                    Text("Connected to ${uiState.connectedDevice?.device?.name}")
                    Button(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Disconnect")
                    }
                }

            } else {
                Text(
                    "Please enable Bluetooth, Location, and grant necessary permissions to proceed.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 20.dp)
                )
                // Optionally, add buttons here to re-trigger checks or requests
                // if the user navigates away and comes back.
                Button(onClick = { viewModel.checkInitialStates() }) {
                    Text("Re-check prerequisites")
                }
            }
        }
    }
}

@Composable
fun GreetingAndPrerequisites(
    uiState: MainUiState,
    onEnableBluetoothClick: () -> Unit,
    onEnableLocationClick: () -> Unit,
    onRequestPermissionsClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
        Text("Welcome!", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("This app needs the following to find nearby Bluetooth devices:", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))

        PrerequisiteItem(
            label = "Bluetooth",
            isMet = uiState.bluetoothEnabled,
            actionText = "Enable Bluetooth",
            onActionClick = onEnableBluetoothClick
        )
        PrerequisiteItem(
            label = "Location Services",
            isMet = uiState.locationEnabled,
            actionText = "Enable Location",
            onActionClick = onEnableLocationClick
        )
        PrerequisiteItem(
            label = "Required Permissions",
            isMet = uiState.requiredPermissionsGranted,
            actionText = "Grant Permissions",
            onActionClick = onRequestPermissionsClick
        )
    }
}

@Composable
fun PrerequisiteItem(
    label: String,
    isMet: Boolean,
    actionText: String,
    onActionClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Text("$label: ", fontWeight = FontWeight.Bold)
            Text(
                if (isMet) "Enabled/Granted" else "Disabled/Not Granted",
                color = if (isMet) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        if (!isMet) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onActionClick) {
                Text(actionText)
            }
        }
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun DeviceList(devices: List<BluetoothDeviceWrapper>, onDeviceClick: (BluetoothDeviceWrapper) -> Unit) {
    val scrollableState = rememberScrollState(0)
    if (devices.isNotEmpty()) {
        Text("Found Devices:", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        LazyColumn(modifier = Modifier
            .fillMaxWidth()
            .scrollable(state = scrollableState, orientation = Orientation.Vertical)
            .heightIn(max = 300.dp)) { // Limit height
            items(devices, key = { it.device.address }) { deviceWrapper ->
                DeviceRow(deviceWrapper = deviceWrapper, onClick = {
                    onDeviceClick(deviceWrapper)
                    Log.d("MainScreen", "Clicked on device: ${deviceWrapper.device}")
                })
                HorizontalDivider()
            }
        }
    } else {
        // Optionally show a message if no devices found yet while scanning or after scan.
        Text("No devices found.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
    }
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
@Composable
fun DeviceRow(deviceWrapper: BluetoothDeviceWrapper, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(deviceWrapper.device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(deviceWrapper.device.address, style = MaterialTheme.typography.bodySmall)
        }
        // You could add an RSSI indicator or a connect button here
    }
}