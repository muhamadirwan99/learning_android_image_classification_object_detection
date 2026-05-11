package com.dicoding.picodiploma.mycamera

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.dicoding.picodiploma.mycamera.databinding.ActivityCameraBinding
import org.tensorflow.lite.task.gms.vision.detector.Detection
import java.text.NumberFormat
import java.util.concurrent.Executors

/**
 * Activity yang bertanggung jawab menampilkan kamera dan menerima hasil deteksi.
 *
 * **Mengapa dipisah dari detektor:**
 * - `CameraActivity` hanya fokus pada alur CameraX, UI binding, dan
 *   menampilkan hasil. Logika model/deteksi dipindahkan ke
 *   `ObjectDetectorHelper` agar tanggung jawab jelas (separation of concerns).
 *
 * **Catatan integrasi:**
 * - `ActivityCameraBinding` berasal dari layout `activity_camera.xml`.
 */
class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    // Pilih kamera belakang sebagai default; dapat diubah jika ingin kamera depan.
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    // Helper yang mengenkapsulasi semua logika deteksi objek.
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    /**
     * Lifecycle onCreate: hanya inisialisasi view binding di sini.
     *
     * **Mengapa tidak langsung start camera di onCreate():**
     * - Start kamera dilakukan di onResume() agar ketika Activity kembali
     *   dari background, kamera otomatis dimulai ulang (benar untuk UI/UX).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

    }

    public override fun onResume() {
        super.onResume()
        hideSystemUI()
        startCamera()
    }

    private fun startCamera() {
        // Inisialisasi helper deteksi di awal startCamera agar lifecycle
        // binding dan listener sudah siap menerima hasil.
        objectDetectorHelper = ObjectDetectorHelper(
            context = this,
            detectorListener = object : ObjectDetectorHelper.DetectorListener {
                override fun onError(error: String) {
                    // Pastikan update UI dijalankan di UI thread.
                    runOnUiThread {
                        Toast.makeText(this@CameraActivity, error, Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResults(
                    results: MutableList<Detection>?,
                    inferenceTime: Long,
                    imageHeight: Int,
                    imageWidth: Int,
                ) {
                    // Semua perubahan UI (overlay, text) harus di UI thread.
                    runOnUiThread {
                        results?.let { it ->
                            if (it.isNotEmpty() && it[0].categories.isNotEmpty()) {
                                // Cetak hasil ke console untuk debugging ringan.
                                println(it)

                                // Kirim hasil ke view overlay agar bounding box
                                // dan label dapat digambar.
                                binding.overlay.setResults(
                                    results, imageHeight, imageWidth,
                                )

                                // Susun string hasil deteksi untuk ditampilkan pada UI.
                                val builder = StringBuilder()
                                for (result in results) {
                                    val displayResult =
                                        "${result.categories[0].label} " + NumberFormat.getPercentInstance()
                                            .format(result.categories[0].score).trim()
                                    builder.append("$displayResult \n")
                                }

                                binding.tvResult.text = builder.toString()
                                binding.tvResult.visibility = View.VISIBLE
                                binding.tvInferenceTime.text = "$inferenceTime ms"
                            } else {
                                // Jika tidak ada hasil, bersihkan overlay dan teks.
                                binding.overlay.clear()
                                binding.tvResult.text = ""
                                binding.tvInferenceTime.text = ""
                            }
                        }
                    }
                }
            }
        )

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()
            // Konfigurasi ImageAnalysis:
            // - setTargetRotation memastikan rotasi frame sesuai dengan display
            // - STRATEGY_KEEP_ONLY_LATEST menghindari antrean frame yang menumpuk
            // - OUTPUT_IMAGE_FORMAT_RGBA_8888 dipilih karena helper mengasumsikan
            //   buffer yang sesuai untuk konversi ke Bitmap.
            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(binding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            // Gunakan single thread executor agar inferensi berjalan serial dan
            // tidak saling tumpang tindih (mengurangi beban memori/CPU).
            imageAnalyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                objectDetectorHelper.detectObject(image)
            }

            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Toast.makeText(
                    this@CameraActivity,
                    "Gagal memunculkan kamera.",
                    Toast.LENGTH_SHORT
                ).show()
                Log.e(TAG, "startCamera: ${exc.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
        supportActionBar?.hide()
    }

    companion object {
        private const val TAG = "CameraActivity"
        const val EXTRA_CAMERAX_IMAGE = "CameraX Image"
        const val CAMERAX_RESULT = 200
    }
}