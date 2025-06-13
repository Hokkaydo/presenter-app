package be.hokkaydo.presenter

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat


class MainActivityOld : AppCompatActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var configurationChange = false
    private lateinit var foregroundServiceIntent: Intent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        return
        setContentView(R.layout.activity_main)

        if(!isLocationEnabled())
            Toast.makeText(this, "Please enable location services", Toast.LENGTH_SHORT).show()

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter

        if (!configurationChange) {
            foregroundServiceIntent = Intent(ForegroundService.ACTION_FOREGROUND_WAKELOCK).setClass(this, ForegroundService::class.java)
            startService(foregroundServiceIntent)
        }

        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val toRequest = permissions.filter { permission ->
            ActivityCompat.checkSelfPermission(
                this,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray<String>(), 1)
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityIfNeeded(enableBtIntent, 2)
        }

        scanForDevices()
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == 1 && grantResults.isNotEmpty()) {
            for (result in grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }
            scanForDevices()
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun scanForDevices() {
        val scanner = bluetoothAdapter.bluetoothLeScanner
        val deviceList = findViewById<ListView>(R.id.deviceList)
        val devices = mutableListOf<BluetoothDevice>()
        val names = mutableListOf<String>()

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        deviceList.adapter = adapter

        val callback = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.name != null)
                    Log.d("MainActivity", "Found device: ${device.name} (${device.address})")
                if (!devices.contains(device) && device.name != null) {
                    devices.add(device)
                    names.add("${device.name} (${device.address})")
                    adapter.notifyDataSetChanged()
                }
            }
        }

        scanner.startScan(callback)

        deviceList.setOnItemClickListener { _, _, position, _ ->
            val device = devices[position]
            scanner.stopScan(callback)
            Toast.makeText(this, "Connecting to ${device.name} (${device.address})", Toast.LENGTH_SHORT).show()
            setContentView(R.layout.ble_connected)
//            BLEManager.connectToDevice(device, this)
        }
    }

    override fun onDestroy() {
        configurationChange =
            if (isChangingConfigurations)
                true
            else {
                stopService(foregroundServiceIntent)
                false
            }
        super.onDestroy()
    }
}
