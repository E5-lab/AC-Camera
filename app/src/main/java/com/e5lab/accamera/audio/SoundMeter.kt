package com.e5lab.accamera.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Live sound-pressure-level estimator built on the microphone.
 *
 * The raw microphone signal is converted to an RMS level and then to a dB value. Phone microphones
 * are not calibrated to an absolute reference, so a [calibrationOffsetDb] is added to approximate
 * real SPL. Calibrate the offset against a calibrated sound level meter for accurate readings.
 *
 * Note: this implements a flat (Z-weighted) estimate. A-weighting can be added later via a
 * pre-emphasis filter before the RMS step.
 */
class SoundMeter(private val calibrationOffsetDb: Double) {

    private var recorder: AudioRecord? = null
    private val sampleRate = 44100
    private var bufferSize = 0
    private var readJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Smoothed (moving-average) dB reading
    private val _db = MutableStateFlow(0.0)
    val db: StateFlow<Double> = _db.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val recent = ArrayDeque<Double>()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (_isRunning.value) return
        try {
            bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (bufferSize <= 0) bufferSize = sampleRate

            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                recorder?.release()
                recorder = null
                return
            }
            recorder?.startRecording()
            _isRunning.value = true
            recent.clear()
            _db.value = 0.0
            readJob = scope.launch { readLoop() }
        } catch (_: SecurityException) {
            stop()
        } catch (_: Exception) {
            stop()
        }
    }

    private suspend fun readLoop() {
        val buffer = ShortArray(bufferSize)
        while (scope.isActive && _isRunning.value) {
            val read = recorder?.read(buffer, 0, buffer.size) ?: 0
            if (read <= 0) {
                delay(50)
                continue
            }
            val rms = computeRms(buffer, read)
            val rawDb = if (rms > 0) 20 * log10(rms / 32768.0) else -90.0
            val spl = rawDb + calibrationOffsetDb

            recent.addLast(spl)
            if (recent.size > 8) recent.removeFirst()
            _db.value = recent.average()

            delay(120)
        }
    }

    private fun computeRms(buffer: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / len)
    }

    fun stop() {
        _isRunning.value = false
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        readJob?.cancel()
        readJob = null
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
