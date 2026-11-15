package com.samsungcam.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.Surface
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
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
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
    private var provider: ProcessCameraProvider? = null
    private var tierSizes = mapOf<Int, Size>()      // MP-Stufe -> echte Größe des Geräts
    private var selectedMp = 12
    private lateinit var resGroup: ChipGroup

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
        resGroup = findViewById(R.id.resGroup)
        resGroup.setOnCheckedStateChangeListener { _, ids ->
            val mp = when (ids.firstOrNull()) { R.id.chip50 -> 50; R.id.chip100 -> 100; else -> 12 }
            if (mp == selectedMp) return@setOnCheckedStateChangeListener
            val size = tierSizes[mp]
            if (size == null) {
                Toast.makeText(this, "Dein Handy bietet $mp MP für Apps nicht an.", Toast.LENGTH_LONG).show()
                resGroup.check(chipFor(selectedMp))
            } else {
                selectedMp = mp
                Toast.makeText(this, "$mp MP: ${size.width}×${size.height}", Toast.LENGTH_SHORT).show()
                bindUseCases()
            }
        }

        chipFollow.setOnCheckedChangeListener { _, on -> framer?.follow = on }
        chipAutoZoom.setOnCheckedChangeListener { _, on -> framer?.autoZoom = on }
        chipHorizon.setOnCheckedChangeListener { _, on ->
            overlay.showHorizon = on
            applyHorizonToPreview()
        }

        horizon = HorizonSensor(this).also {
            it.onChange = { a, q ->
                if (a == 0f && overlay.tiltDeg != 0f && chipHorizon.isChecked)
                    previewView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                overlay.tiltDeg = a
                applyHorizonToPreview()
                imageCapture?.targetRotation = surfaceRotation(q) // Querformat-Fotos aufrecht speichern
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

    private fun chipFor(mp: Int) = when (mp) { 50 -> R.id.chip50; 100 -> R.id.chip100; else -> R.id.chip12 }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = future.get()
            detectTiers()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    /** Fragt das Gerät, welche JPEG-Größen es Apps wirklich anbietet, und ordnet sie 12/50/100 MP zu. */
    private fun detectTiers() {
        val p = provider ?: return
        val info = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).firstOrNull() ?: return
        val map = Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val all = (map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() +
            map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()).map { it.width to it.height }
        tierSizes = ResolutionTiers.tiers.mapNotNull { mp ->
            ResolutionTiers.pick(all, mp)?.let { mp to Size(it.first, it.second) }
        }.toMap()
        listOf(12 to R.id.chip12, 50 to R.id.chip50, 100 to R.id.chip100).forEach { (mp, id) ->
            findViewById<Chip>(id).alpha = if (tierSizes.containsKey(mp)) 1f else 0.4f
        }
    }

    private fun bindUseCases() {
        val p = provider ?: return
        val target = tierSizes[selectedMp]
        val captureSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .apply {
                if (target != null) setResolutionFilter(ResolutionFilter { sizes, _ ->
                    sizes.filter { it.width == target.width && it.height == target.height }.ifEmpty { sizes }
                })
            }.build()

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(captureSelector)
            .setTargetRotation(surfaceRotation(horizon.quadrant))
            .build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            .also { it.setAnalyzer(analysisExecutor, ::analyze) }

        try {
            p.unbindAll()
            val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            camera = cam
            imageCapture = capture
            lastCrop = null
            val sensor = Camera2CameraInfo.from(cam.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (sensor != null) {
                framer = AutoFramer(sensor.width(), sensor.height()).also {
                    it.follow = chipFollow.isChecked
                    it.autoZoom = chipAutoZoom.isChecked
                }
            } else Log.w(TAG, "Sensorgröße unbekannt – Follow/Auto-Zoom deaktiviert")
        } catch (e: Exception) {
            Log.e(TAG, "Binden mit $selectedMp MP fehlgeschlagen", e)
            if (selectedMp != 12) {
                Toast.makeText(this, "$selectedMp MP lässt sich auf diesem Handy nicht nutzen – zurück auf 12 MP.", Toast.LENGTH_LONG).show()
                selectedMp = 12
                resGroup.check(R.id.chip12)
                bindUseCases()
            }
        }
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

    private fun newPhotoValues(prefix: String) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SamsungCam2")
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val tilt = horizonAngle() // Winkel im Moment des Auslösens
        if (abs(tilt) <= 0.2f) {
            // Gerade Aufnahme: JPEG direkt speichern (kein Neu-Kodieren, spart Speicher bei 50/100 MP)
            val opts = ImageCapture.OutputFileOptions.Builder(
                contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newPhotoValues("SCAM")
            ).build()
            capture.takePicture(opts, ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        lastPhoto = r.savedUri
                        Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_SHORT).show()
                    }
                    override fun onError(e: ImageCaptureException) {
                        Log.e(TAG, "Aufnahme fehlgeschlagen", e)
                        Toast.makeText(this@MainActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
                    }
                })
            return
        }
        capture.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rot = image.imageInfo.rotationDegrees
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    image.close()
                    saveExecutor.execute { saveLeveled(bytes, rot, tilt) }
                }
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Aufnahme fehlgeschlagen", e)
                }
            })
    }

    private fun saveLeveled(jpeg: ByteArray, rotation: Int, tilt: Float) {
        var ok = false
        try {
            val src = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } catch (oom: OutOfMemoryError) { // 50/100 MP: notfalls halbe Auflösung
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
            val bmp = renderLeveled(src, rotation, tilt)
            src.recycle()
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, newPhotoValues("SCAM"))
            ok = uri != null && contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } == true
            if (ok) lastPhoto = uri
        } catch (e: Throwable) {
            Log.e(TAG, "Speichern fehlgeschlagen", e)
        }
        runOnUiThread {
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

        /** Gerätehaltung (Uhrzeigersinn, Grad) -> Surface.ROTATION_* für die Aufnahme. */
        fun surfaceRotation(quadrant: Int) = when (quadrant) {
            90 -> Surface.ROTATION_270
            -90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            else -> Surface.ROTATION_0
        }

        /**
         * Dreht das Sensorbild in einem Schritt: [rotation] (aufrecht machen) + [tilt] (Horizont gerade),
         * skaliert so, dass keine Ränder entstehen.
         */
        fun renderLeveled(src: Bitmap, rotation: Int, tilt: Float): Bitmap {
            val swap = rotation % 180 != 0
            val ow = if (swap) src.height else src.width
            val oh = if (swap) src.width else src.height
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val s = coverScale(tilt, ow.toFloat(), oh.toFloat())
            val m = Matrix().apply {
                postTranslate(-src.width / 2f, -src.height / 2f)
                postRotate(rotation + tilt)
                postScale(s, s)
                postTranslate(ow / 2f, oh / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}
