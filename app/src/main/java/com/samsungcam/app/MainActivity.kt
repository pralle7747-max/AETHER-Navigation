package com.samsungcam.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.ScaleGestureDetector
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var zoomLabel: TextView
    private lateinit var chipFollow: Chip
    private lateinit var chipAutoZoom: Chip
    private lateinit var chipHorizon: Chip

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var framer: AutoFramer? = null
    private var lastCrop: Crop? = null
    private var lastPhoto: Uri? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val saveExecutor = Executors.newSingleThreadExecutor()
    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
    }
    private lateinit var horizon: HorizonSensor

    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else {
                Toast.makeText(this, R.string.no_permission, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        zoomLabel = findViewById(R.id.zoomLabel)
        chipFollow = findViewById(R.id.chipFollow)
        chipAutoZoom = findViewById(R.id.chipAutoZoom)
        chipHorizon = findViewById(R.id.chipHorizon)

        chipFollow.setOnCheckedChangeListener { _, on -> framer?.follow = on }
        chipAutoZoom.setOnCheckedChangeListener { _, on -> framer?.autoZoom = on }
        chipHorizon.setOnCheckedChangeListener { _, on ->
            overlay.showHorizon = on
            applyHorizonToPreview()
        }

        horizon = HorizonSensor(this).also {
            it.onChange = { a ->
                overlay.tiltDeg = a
                applyHorizonToPreview()
            }
        }

        // Pinch = manueller Zoom (wirkt, wenn Auto-Zoom aus ist)
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                framer?.let { it.manualZoom = (it.manualZoom * d.scaleFactor).coerceIn(1f, 5f) }
                return true
            }
        })
        previewView.setOnTouchListener { _, e -> pinch.onTouchEvent(e); true }

        findViewById<android.view.View>(R.id.btnShutter).setOnClickListener { takePhoto() }
        findViewById<android.view.View>(R.id.btnEraser).setOnClickListener {
            val uri = lastPhoto
            if (uri == null) Toast.makeText(this, R.string.no_photo, Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, EditorActivity::class.java).setData(uri))
        }

        findViewById<android.view.View>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() { super.onResume(); horizon.start() }
    override fun onPause() { horizon.stop(); super.onPause() }
    override fun onDestroy() {
        detector.close(); analysisExecutor.shutdown(); saveExecutor.shutdown(); super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }

            provider.unbindAll()
            val cam = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis
            )
            camera = cam
            imageCapture = capture

            val sensor = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensor != null) {
                framer = AutoFramer(sensor.width(), sensor.height()).also {
                    it.follow = chipFollow.isChecked
                    it.autoZoom = chipAutoZoom.isChecked
                }
            } else Log.w(TAG, "Sensorgröße unbekannt – Follow/Auto-Zoom deaktiviert")
        }, ContextCompat.getMainExecutor(this))
    }

    /** Läuft auf analysisExecutor; Ergebnis wird auf dem Main-Thread verarbeitet. */
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val f = framer
        if (media == null || f == null) { proxy.close(); return }
        val rot = proxy.imageInfo.rotationDegrees
        val upW = if (rot % 180 == 0) proxy.width else proxy.height
        val upH = if (rot % 180 == 0) proxy.height else proxy.width

        // Gesichtserkennung nur, wenn sie gebraucht wird
        if (!f.follow && !f.autoZoom) {
            proxy.close()
            runOnUiThread { applyCrop(f.update(null, SystemClock.uptimeMillis())) }
            return
        }
        detector.process(InputImage.fromMediaImage(media, rot))
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { faces ->
                val subject = if (faces.isEmpty()) null else {
                    val l = faces.minOf { it.boundingBox.left } / upW.toFloat()
                    val t = faces.minOf { it.boundingBox.top } / upH.toFloat()
                    val r = faces.maxOf { it.boundingBox.right } / upW.toFloat()
                    val b = faces.maxOf { it.boundingBox.bottom } / upH.toFloat()
                    f.subjectFromUpright(l, t, r, b, rot)
                }
                applyCrop(f.update(subject, SystemClock.uptimeMillis()))
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun applyCrop(c: Crop) {
        if (c == lastCrop) return
        lastCrop = c
        camera?.let {
            Camera2CameraControl.from(it.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        CaptureRequest.SCALER_CROP_REGION,
                        android.graphics.Rect(c.left, c.top, c.right, c.bottom)
                    ).build()
            )
        }
        zoomLabel.text = "%.1f×".format(framer?.zoom ?: 1f)
    }

    private fun horizonAngle() = if (chipHorizon.isChecked) horizon.angleDeg else 0f

    private fun applyHorizonToPreview() {
        val a = horizonAngle()
        previewView.rotation = a
        val s = coverScale(a, previewView.width.toFloat(), previewView.height.toFloat())
        previewView.scaleX = s; previewView.scaleY = s
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val angle = horizonAngle() // Winkel im Moment des Auslösens
        capture.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rot = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    saveExecutor.execute { process(raw, rot, angle) }
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Aufnahme fehlgeschlagen", e)
                }
            })
    }

    private fun process(raw: Bitmap, rotation: Int, tilt: Float) {
        var bmp = raw
        if (rotation != 0) {
            bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        if (abs(tilt) > 0.2f) bmp = levelHorizon(bmp, tilt)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SCAM_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SamsungCam2")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        val ok = uri != null && contentResolver.openOutputStream(uri)?.use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
        } == true
        runOnUiThread {
            if (ok) lastPhoto = uri
            Toast.makeText(this, if (ok) R.string.saved else R.string.save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "SamsungCam2"

        /** Skalierung, damit ein um [deg] gedrehtes w×h-Rechteck das Original voll abdeckt. */
        fun coverScale(deg: Float, w: Float, h: Float): Float {
            if (w <= 0f || h <= 0f) return 1f
            val r = Math.toRadians(abs(deg).toDouble())
            return (cos(r) + sin(r) * max(w / h, h / w)).toFloat()
        }

        /** Dreht das Bild um [deg] (Uhrzeigersinn) und skaliert so, dass keine Ränder entstehen. */
        fun levelHorizon(src: Bitmap, deg: Float): Bitmap {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val s = coverScale(deg, src.width.toFloat(), src.height.toFloat())
            val m = Matrix().apply {
                postRotate(deg, src.width / 2f, src.height / 2f)
                postScale(s, s, src.width / 2f, src.height / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}
