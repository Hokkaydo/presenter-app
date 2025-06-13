package be.hokkaydo.presenter.socket

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.net.Socket


class MainActivity : AppCompatActivity() {
    private lateinit var ipInput: EditText
    private lateinit var startButton: Button
    private var socket = Socket()
    private var writer = PrintWriter(System.out, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        ipInput = findViewById(R.id.ipAddressInput)
//        startButton = findViewById(R.id.startButton)
//
//        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
//        ipInput.setText(prefs.getString("ipAddress", ""))
//
//        //initSocket(prefs.getString("ipAddress", ""))
//
//        startButton.setOnClickListener {
//            val ip = ipInput.text.toString()
//            initSocket(ip)
//        }
    }

    private fun initSocket(ip: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (socket.isConnected) {
                    socket.close()
                }
                socket = Socket(ip, 5001)
                writer = PrintWriter(socket.getOutputStream(), true)
                Log.i("Main", "Connected !")
            } catch (e: Exception) {
                Log.e("Main", "Socket error: ${e.message}")
            }
        }
    }


    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> sendEvent("DOWN")
            KeyEvent.KEYCODE_VOLUME_UP -> sendEvent("UP")
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun sendEvent(direction: String): Boolean {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                writer.println(direction)
                Log.d("Main", "Sent $direction")
            } catch (e: Exception) {
                Log.e("Main", "Error sending direction: ${e.message}")
            }
        }
        return true
    }
}