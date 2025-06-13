package be.hokkaydo.presenter

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager.STREAM_MUSIC
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import android.os.PowerManager.WakeLock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission

class ForegroundService : Service() {
    private lateinit var volumeButtonHelper: VolumeButtonHelper
    private var notificationManager: NotificationManager? = null

    companion object {
        var wakeLock: WakeLock? = null

        const val TAG = "VolumeButtonHelper"
        const val ACTION_FOREGROUND_WAKELOCK = "be.hokkaydo.presenter.FOREGROUND_WAKELOCK"
        const val ACTION_FOREGROUND = "be.hokkaydo.presenter.FOREGROUND"
        const val WAKE_LOCK_TAG = "be.hokkaydo.presenter:wake-service"
        const val CHANNEL_ID = "be.hokkaydo.presenter:foreground-channel"
    }

    override fun onBind(p0: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_ID,
            NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })

        volumeButtonHelper = VolumeButtonHelper(this, STREAM_MUSIC, enabledScreenOff = true)
        volumeButtonHelper.registerVolumeChangeListener(
            object : VolumeButtonHelper.VolumeChangeListener {
                @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
                override fun onVolumeChange(direction: VolumeButtonHelper.Direction) {
                    Log.i(TAG, "onVolumeChange: $direction")
                    if (direction != VolumeButtonHelper.Direction.RELEASE)
                        BLEManager.instance?.sendData(direction.command)
                }

                override fun onVolumePress(count: Int) {
                    Log.i(TAG, "onVolumePress: $count")
                }

                override fun onSinglePress() {
                    Log.i(TAG, "onSinglePress")
                }

                override fun onDoublePress() {
                    Log.i(TAG, "onDoublePress")
                }

                override fun onLongPress() {
                    Log.i(TAG, "onLongPress")
                }
            }
        )
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int
    {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_FOREGROUND || intent?.action == ACTION_FOREGROUND_WAKELOCK) {
            startForeground(R.string.foreground_service_started, Notification.Builder(this, CHANNEL_ID).build())
        }

        if (intent?.action == ACTION_FOREGROUND_WAKELOCK) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(
                    PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG)
                wakeLock?.acquire()
            }
            else {
                releaseWakeLock()
            }
        }
        return START_STICKY
    }

    private fun releaseWakeLock() {
        wakeLock?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        volumeButtonHelper.unregisterReceiver()
        volumeButtonHelper.finalizeMediaPlayer()
    }
}
