package be.hokkaydo.presenter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.TRANSPORT_LE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.serializer
import java.util.UUID

internal class BLEManager(val context: Context, val bluetoothAdapter: BluetoothAdapter) {

    private var gatt: BluetoothGatt? = null
    private val serviceUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private val characteristicUUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
    private var services: List<BluetoothGattService>? = null
    private var scanCallback : ScanCallback? = null

    companion object {
        @SuppressLint("StaticFieldLeak")
        var instance: BLEManager? = null
        fun init(context: Context, bluetoothAdapter: BluetoothAdapter): BLEManager {
            if(instance == null)
                instance = BLEManager(context.applicationContext, bluetoothAdapter)
            Log.d("BLEManager", "BLEManager initialized")
            return instance!!
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun startScan(onDeviceFound: (BluetoothDevice) -> Unit) {
        scanCallback = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onDeviceFound(result.device)
            }
        }
        bluetoothAdapter.bluetoothLeScanner.startScan (scanCallback)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() = bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(device: BluetoothDevice, onConnected: () -> Unit) {
        if(gatt != null) {
            services = null
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                    onConnected()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && gatt.services.isNotEmpty()) {
                    services = gatt.services
                    Log.d("BLEManager", "Services in onServicesDiscovered $services")
                    for (service in services ?: return) {
                        Log.d("BLEManager", "Service (${service.uuid}: ${service.characteristics.map { c -> c.uuid }}")
                    }
                }
            }
        }, TRANSPORT_LE)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        services = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendData(data: ByteArray) {
        gatt?.discoverServices()
        val service = services?.find { s -> s.uuid == serviceUUID }
        if (service == null) {
            Log.d("BLEManager", "Service not found")
            Toast.makeText(context, "Service not found", Toast.LENGTH_SHORT).show()
            return
        }
        val characteristic = service.getCharacteristic(characteristicUUID)

        if (characteristic == null) {
            Log.d("BLEManager", "Characteristic not found")
            Toast.makeText(context, "Characteristic not found", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            characteristic.value = data
            gatt?.writeCharacteristic(characteristic)
        }
    }
}