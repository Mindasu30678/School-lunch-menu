package com.example.silentcamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BackgroundCameraService : Service(), LifecycleOwner {

    companion object {
        const val ACTION_STOP = "com.example.silentcamera.STOP_BACKGROUND"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "bg_camera"

        fun isRunning(ctx: Context): Boolean = runningInstance != null
        private var runningInstance: BackgroundCameraService? = null
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var cameraProvider: ProcessCameraProvider? = null
    private var recording: Recording? = null
    private var imageCapture: ImageCapture? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var burstRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val mode = Prefs.shortcutMode(this)
        if (mode == "video") startBackgroundVideo() else startBurstPhoto()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun cameraSelector(): CameraSelector {
        val lens = Prefs.backgroundLens(this)
        return if (lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA
    }

    // --- Video ---

    private fun startBackgroundVideo() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val quality = when (Prefs.videoQuality(this)) {
                "sd" -> Quality.SD
                "hd" -> Quality.HD
                "uhd" -> Quality.UHD
                else -> Quality.FHD
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, cameraSelector(), videoCapture)

            val file = newVideoFile()
            val outputOptions = FileOutputOptions.Builder(file).build()
            recording = videoCapture.output
                .prepareRecording(this, outputOptions)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        scanMedia(file)
                    }
                }
        }, ContextCompat.getMainExecutor(this))
    }

    // --- Burst photo ---

    private fun startBurstPhoto() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val capture = buildImageCapture()
            imageCapture = capture
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this, cameraSelector(), capture)
            scheduleBurst()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildImageCapture(): ImageCapture {
        val ratio = when (Prefs.photoQuality(this)) {
            "low" -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            "medium" -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            else -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
        return ImageCapture.Builder().setCaptureMode(ratio).build()
    }

    private fun scheduleBurst() {
        val intervalMs = Prefs.burstInterval(this) * 1000L
        burstRunnable = object : Runnable {
            override fun run() {
                takeSilentPhoto()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.post(burstRunnable!!)
    }

    private fun takeSilentPhoto() {
        val capture = imageCapture ?: return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val prev = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)

        val file = newPhotoFile()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, prev, 0)
                scanMedia(file)
            }
            override fun onError(exc: ImageCaptureException) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, prev, 0)
                Log.e("BgCamera", "burst photo error", exc)
            }
        })
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ShortcutReceiver::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_dot)
            .setContentTitle(getString(R.string.background_active))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.stop), stopPending)
            .build()
    }

    // --- File helpers ---

    private fun newVideoFile(): File {
        val dir = File(getExternalFilesDir(null), "Movies/SilentCamera").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "VID_$ts.mp4")
    }

    private fun newPhotoFile(): File {
        val dir = File(getExternalFilesDir(null), "Pictures/SilentCamera").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "IMG_$ts.jpg")
    }

    private fun scanMedia(file: File) {
        android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
    }

    override fun onDestroy() {
        burstRunnable?.let { handler.removeCallbacks(it) }
        recording?.stop()
        cameraProvider?.unbindAll()
        executor.shutdown()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        runningInstance = null
        super.onDestroy()
    }
}
