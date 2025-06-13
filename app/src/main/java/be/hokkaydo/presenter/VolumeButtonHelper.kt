package be.hokkaydo.presenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.media.MediaPlayer
import android.util.Log
import be.hokkaydo.presenter.ForegroundService.Companion.TAG
import be.hokkaydo.presenter.VolumeButtonHelper.Direction.DOWN
import be.hokkaydo.presenter.VolumeButtonHelper.Direction.RELEASE
import be.hokkaydo.presenter.VolumeButtonHelper.Direction.UP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VolumeButtonHelper(private var context: Context,
                         private var stream: Int? = null,
                         enabledScreenOff: Boolean) {
    companion object {
        const val VOLUME_CHANGE_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
    }

    enum class Direction(val command: ByteArray) {
        UP(byteArrayOf(0x01)),
        DOWN(byteArrayOf(0x02)),
        RELEASE(byteArrayOf(0x03));
    }

    private var mediaPlayer: MediaPlayer? = null
    private var volumeBroadCastReceiver: VolumeBroadCastReceiver? = null
    private var volumeChangeListener: VolumeChangeListener? = null

    private val audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var priorVolume = -1
    private var volumePushes = 0.0
    private var longPressReported = false

    var doublePressTimeout = 350L
    var buttonReleaseTimeout = 100L

    private var minVolume = -1

    private var maxVolume = -1

    private var halfVolume = -1

    var currentVolume = -1
        private set


    // ---------------------------------------------------------------------------------------------
    init {
        if (audioManager != null) {
            minVolume = audioManager.getStreamMinVolume(STREAM_MUSIC)
            maxVolume = audioManager.getStreamMaxVolume(STREAM_MUSIC)
            halfVolume = (minVolume + maxVolume) / 2

            /*************************************
             * BroadcastReceiver does not get triggered for VOLUME_CHANGE_ACTION
             * if the screen is off and no media is playing.
             * Playing a silent media file solves that.
             *************************************/
            if (enabledScreenOff) {
                mediaPlayer = MediaPlayer.create(context, R.raw.silence)

                if (mediaPlayer != null) {
                    mediaPlayer?.isLooping = true
                    mediaPlayer?.start()
                }
                else
                    Log.e(TAG, "Unable to initialize MediaPlayer")
            }
        }
        else
            Log.e(TAG, "Unable to initialize AudioManager")
    }

    // ---------------------------------------------------------------------------------------------
    fun registerVolumeChangeListener(volumeChangeListener: VolumeChangeListener) {
        if (volumeBroadCastReceiver == null) {
            this.volumeChangeListener = volumeChangeListener
            volumeBroadCastReceiver = VolumeBroadCastReceiver()

            val filter = IntentFilter()
            filter.addAction(VOLUME_CHANGE_ACTION)

            context.registerReceiver(volumeBroadCastReceiver, filter)
        }
    }

    // ---------------------------------------------------------------------------------------------
    fun unregisterReceiver() {
        if (volumeBroadCastReceiver != null) {
            context.unregisterReceiver(volumeBroadCastReceiver)
            volumeBroadCastReceiver = null
        }
    }

    // ---------------------------------------------------------------------------------------------
    fun finalizeMediaPlayer() {
        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    // ---------------------------------------------------------------------------------------------
    fun onVolumePress(count: Int) {
        when (count) {
            1 -> volumeChangeListener?.onSinglePress()
            2 -> volumeChangeListener?.onDoublePress()
            else -> volumeChangeListener?.onVolumePress(count)
        }
    }

    // ---------------------------------------------------------------------------------------------
    interface VolumeChangeListener {
        fun onVolumeChange(direction: Direction)
        fun onVolumePress(count: Int)
        fun onSinglePress()
        fun onDoublePress()
        fun onLongPress()
    }
    var maxUpCount = 0
    var minDownCount = 0

    // ---------------------------------------------------------------------------------------------
    inner class VolumeBroadCastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (stream == null || intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) == stream) {
                currentVolume = audioManager?.getStreamVolume(STREAM_MUSIC) ?: -1
                maxVolume = audioManager?.getStreamMaxVolume(STREAM_MUSIC) ?: -1

                if (currentVolume != -1) {
                    if (currentVolume != priorVolume) {
                        if (currentVolume > priorVolume)
                            volumeChangeListener?.onVolumeChange(UP)
                        else if (currentVolume < priorVolume)
                            volumeChangeListener?.onVolumeChange(DOWN)
                        priorVolume = currentVolume
                    } else if (currentVolume == 0) {
                        minDownCount++
                        if (minDownCount == 2) {
                            volumeChangeListener?.onVolumeChange(DOWN)
                            minDownCount = 0
                        }
                    }
                    else if (currentVolume == maxVolume) {
                        maxUpCount++
                        if (maxUpCount == 2) {
                            volumeChangeListener?.onVolumeChange(UP)
                            maxUpCount = 0
                        }
                    }

                    volumePushes += 0.5 // For some unknown reason (to me), onReceive gets called twice for every button push

                    if (volumePushes == 0.5) {
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(doublePressTimeout - buttonReleaseTimeout)
                            buttonDown()
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------------------------------------
        private fun buttonDown() {
            val startVolumePushes = volumePushes

            CoroutineScope(Dispatchers.Default).launch {
                delay(buttonReleaseTimeout)
                val currentVolumePushes = volumePushes

                if (startVolumePushes != currentVolumePushes) {
                    if (volumePushes > 2 && !longPressReported) {
                        longPressReported = true
                        volumeChangeListener?.onLongPress()
                    }

                    buttonDown()
                }
                else {
                    onVolumePress(volumePushes.toInt())
                    volumeChangeListener?.onVolumeChange(RELEASE)
                    volumePushes = 0.0
                    longPressReported = false
                }
            }
        }
    }
}