package com.example.silentcamera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.silentcamera.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var executor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var isVideoMode = false
    private var isRecording = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private val handler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private val timerRunnable = object : Runnable {
        override fun run() {
            recordingSeconds++
            val m = recordingSeconds / 60
            val s = recordingSeconds % 60
            binding.tvRecordingTime.text = "%02d:%02d".format(m, s)
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var scaleGestureDetector: ScaleGestureDetector

    companion object {
        private val PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        private const val REQUEST_PERMISSIONS = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        executor = Executors.newSingleThreadExecutor()
        setupGestureDetector()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQUEST_PERMISSIONS)
        }

        binding.shutterInner.setOnClickListener { onShutter() }
        binding.btnFlip.setOnClickListener { flipCamera() }
        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.tabPhoto.setOnClickListener { setMode(false) }
        binding.tabVideo.setOnClickListener { setMode(true) }
        binding.imgThumbnail.setOnClickListener { openGallery() }

        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP && !scaleGestureDetector.isInProgress) {
                tapToFocus(event.x, event.y)
            }
            true
        }
    }

    private fun setupGestureDetector() {
        scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val zoom = camera?.cameraInfo?.zoomState?.value
                    val currentZoom = zoom?.zoomRatio ?: 1f
                    camera?.cameraControl?.setZoomRatio(currentZoom * detector.scaleFactor)
                    return true
                }
            })
    }

    private fun tapToFocus(x: Float, y: Float) {
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    private fun setMode(video: Boolean) {
        isVideoMode = video
        if (video) {
            binding.tabVideo.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.tabVideo.background = ContextCompat.getDrawable(this, R.drawable.tab_selected_bg)
            binding.tabPhoto.setTextColor(0xFFAAAAAA.toInt())
            binding.tabPhoto.background = null
            binding.shutterInner.background = ContextCompat.getDrawable(this, R.drawable.shutter_inner_video)
        } else {
            binding.tabPhoto.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.tabPhoto.background = ContextCompat.getDrawable(this, R.drawable.tab_selected_bg)
            binding.tabVideo.setTextColor(0xFFAAAAAA.toInt())
            binding.tabVideo.background = null
            binding.shutterInner.background = ContextCompat.getDrawable(this, R.drawable.shutter_inner)
        }
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        imageCapture = buildImageCapture()

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(currentVideoQuality()))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)

        provider.unbindAll()
        try {
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture)
        } catch (e: Exception) {
            Toast.makeText(this, "相機啟動失敗", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildImageCapture(): ImageCapture {
        val mode = when (Prefs.photoQuality(this)) {
            "low", "medium" -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            else -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
        return ImageCapture.Builder()
            .setCaptureMode(mode)
            .setFlashMode(flashMode)
            .build()
    }

    private fun currentVideoQuality(): Quality = when (Prefs.videoQuality(this)) {
        "sd" -> Quality.SD
        "hd" -> Quality.HD
        "uhd" -> Quality.UHD
        else -> Quality.FHD
    }

    private fun onShutter() {
        if (isVideoMode) toggleRecording() else takePhoto()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val prevVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE)

        val file = newPhotoFile()
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, prevVol, 0)
                scanAndUpdateThumbnail(file)
            }
            override fun onError(exc: ImageCaptureException) {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, prevVol, 0)
            }
        })
    }

    private fun toggleRecording() {
        if (isRecording) {
            recording?.stop()
            recording = null
            isRecording = false
            handler.removeCallbacks(timerRunnable)
            binding.recordingIndicator.visibility = View.GONE
        } else {
            val vc = videoCapture ?: return
            val file = newVideoFile()
            val options = FileOutputOptions.Builder(file).build()
            recording = vc.output
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    when (event) {
                        is VideoRecordEvent.Start -> {
                            isRecording = true
                            recordingSeconds = 0
                            binding.recordingIndicator.visibility = View.VISIBLE
                            handler.post(timerRunnable)
                        }
                        is VideoRecordEvent.Finalize -> {
                            isRecording = false
                            handler.removeCallbacks(timerRunnable)
                            binding.recordingIndicator.visibility = View.GONE
                            scanAndUpdateThumbnail(file)
                        }
                        else -> {}
                    }
                }
        }
    }

    private fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        binding.previewView.animate().scaleX(-1f).setDuration(0).start()
        bindCamera()
        binding.previewView.animate().scaleX(1f).setDuration(200).start()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        val icon = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> android.R.drawable.ic_menu_slideshow
            ImageCapture.FLASH_MODE_ON -> android.R.drawable.ic_menu_camera
            else -> android.R.drawable.ic_menu_help
        }
        binding.btnFlash.setImageResource(icon)
    }

    private fun scanAndUpdateThumbnail(file: File) {
        android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null) { _, _ ->
            handler.post {
                binding.imgThumbnail.setImageURI(Uri.fromFile(file))
            }
        }
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            type = "image/*"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun newPhotoFile(): File {
        val dir = File(getExternalFilesDir(null), "Pictures/SilentCamera").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "IMG_$ts.jpg")
    }

    private fun newVideoFile(): File {
        val dir = File(getExternalFilesDir(null), "Movies/SilentCamera").also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "VID_$ts.mp4")
    }

    private fun allPermissionsGranted() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                showPermissionDialog()
            }
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.permission_required))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    override fun onDestroy() {
        executor.shutdown()
        handler.removeCallbacks(timerRunnable)
        super.onDestroy()
    }
}
