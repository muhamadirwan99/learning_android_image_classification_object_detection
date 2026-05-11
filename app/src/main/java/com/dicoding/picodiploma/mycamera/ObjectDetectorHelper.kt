package com.dicoding.picodiploma.mycamera

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.android.gms.tflite.client.TfLiteInitializationOptions
import com.google.android.gms.tflite.gpu.support.TfLiteGpu
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.gms.vision.TfLiteVision
import androidx.core.graphics.createBitmap
import org.tensorflow.lite.support.image.ops.Rot90Op
import org.tensorflow.lite.task.gms.vision.detector.Detection
import org.tensorflow.lite.task.gms.vision.detector.ObjectDetector

/**
 * Helper untuk menginisialisasi dan menjalankan deteksi objek menggunakan
 * TensorFlow Lite Task Library (vision object detector).
 *
 * **Mengapa kelas ini ada:**
 * - Memisahkan logika inisialisasi TFLite (termasuk dukungan GPU) dari Activity
 *   agar kode UI tetap bersih dan mudah diuji.
 * - Menyediakan titik integrasi tunggal untuk konfigurasi model (threshold,
 *   maxResults, dan nama model) sehingga perubahan konfigurasi tidak menyebar.
 *
 * **Catatan penting:**
 * - ⚠️ NOTE: Inisialisasi `TfLiteVision` dan pemeriksaan GPU dilakukan asinkron. Jika
 *   pemanggilan detect terjadi sebelum inisialisasi selesai, deteksi akan dilewatkan.
 * - Jika ada hubungan dengan file lain: pastikan model `.tflite` (`modelName`) tersedia di
 *   folder `assets` atau lokasi yang sesuai yang dikemas ke dalam APK.
 *
 * @param threshold Batas confidence minimal untuk menampilkan deteksi.
 * @param maxResults Jumlah hasil maksimal yang dikembalikan oleh detector.
 * @param modelName Nama file model TFLite yang digunakan.
 * @param context Context aplikasi / activity untuk inisialisasi resource.
 * @param detectorListener Callback agar Activity menerima hasil atau error.
 */
class ObjectDetectorHelper(
    var threshold: Float = 0.5f,
    var maxResults: Int = 5,
    val modelName: String = "efficientdet_lite0_v1.tflite",
    val context: Context,
    val detectorListener: DetectorListener?
) {
    private var objectDetector: ObjectDetector? = null

    /**
     * Blok inisialisasi yang menjalankan pemeriksaan dukungan GPU dan
     * menginisialisasi TfLiteVision secara asinkron.
     *
     * **Mengapa ini penting:**
     * - Memeriksa ketersediaan GPU memungkinkan penggunaan delegate GPU bila ada,
     *   yang sering mempercepat inferensi pada perangkat yang mendukung.
     * - Inisialisasi asinkron mencegah blocking pada UI thread saat library
     *   melakukan persiapan internal.
     *
     * **Perilaku:**
     * - Jika inisialisasi sukses, akan memanggil `setupObjectDetector()` untuk
     *   membuat instance detektor. Jika gagal, akan meneruskan pesan error via
     *   `detectorListener`.
     */
    init {
        TfLiteGpu.isGpuDelegateAvailable(context).onSuccessTask { gpuAvailable ->
            val optionsBuilder = TfLiteInitializationOptions.builder()
            // Jika GPU tersedia, aktifkan dukungan GPU pada TfLiteVision sehingga
            // implementasi TFLite dapat memanfaatkan delegate bila memungkinkan.
            if (gpuAvailable) {
                optionsBuilder.setEnableGpuDelegateSupport(true)
            }
            TfLiteVision.initialize(context, optionsBuilder.build())
        }.addOnSuccessListener {
            // Setelah TfLiteVision siap, buat object detector.
            setupObjectDetector()
        }.addOnFailureListener {
            // Jika inisialisasi gagal, beri tahu listener agar UI dapat menampilkan
            // pesan error yang sesuai.
            detectorListener?.onError(context.getString(R.string.tflitevision_is_not_initialized_yet))
        }
    }

    /**
     * Membangun konfigurasi `ObjectDetector` dan membuat instance detektor.
     *
     * **Mengapa terpisah:** pisahkan pembuatan opsi/instance agar bisa dipanggil
     * lagi kalau instance awal belum tersedia (mis. setelah inisialisasi asinkron).
     *
     * Hal-hal yang dicakup di sini:
     * - `setScoreThreshold` dan `setMaxResults` mengontrol filter hasil
     *   agar hanya deteksi relevan yang dikembalikan.
     * - `BaseOptions.useGpu()` memberi petunjuk agar detektor mencoba memakai
     *   delegate GPU jika tersedia (sesuai inisialisasi sebelumnya).
     *
     * ⚠️ NOTE: Memanggil `useGpu()` tidak menjamin GPU akan dipakai — itu
     * bergantung pada ketersediaan delegate dan kompatibilitas model/perangkat.
     */
    private fun setupObjectDetector() {
        val optionsBuilder = ObjectDetector.ObjectDetectorOptions.builder()
            .setScoreThreshold(threshold)
            .setMaxResults(maxResults)
        val baseOptionsBuilder = BaseOptions.builder()
            // Meminta agar library mempertimbangkan penggunaan GPU delegate.
            .useGpu()
        optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

        try {
            // Membuat instance detektor dari file model dan opsi yang telah
            // dikonfigurasi. Jika gagal, tangkap IllegalStateException.
            objectDetector = ObjectDetector.createFromFileAndOptions(
                context,
                modelName,
                optionsBuilder.build()
            )
        } catch (e: IllegalStateException) {
            // Beritahu listener agar UI dapat menampilkan pesan kesalahan.
            detectorListener?.onError(context.getString(R.string.image_classifier_failed))
            Log.e(TAG, e.message.toString())
        }
    }

    /**
     * Melakukan deteksi pada satu frame `ImageProxy` yang disediakan oleh CameraX.
     *
     * **Alasan mengapa beberapa pemeriksaan dilakukan:**
     * - Pastikan `TfLiteVision` sudah diinisialisasi sebelum mencoba deteksi.
     * - Jika instance `objectDetector` belum dibuat, panggil `setupObjectDetector()`
     *   sehingga detektor dapat dibuat dinamis (berguna saat inisialisasi asinkron).
     * - Jika detektor tetap null setelah usaha pembuatan ulang, tutup `ImageProxy`
     *   dan hentikan proses untuk menghindari kebocoran buffer.
     *
     * **Proses preprocessing:**
     * - Lakukan rotasi gambar menggunakan `Rot90Op` agar orientasi sesuai
     *   dengan yang diharapkan model. Ini penting karena CameraX memberikan
     *   rotation metadata yang harus disesuaikan sebelum inferensi.
     * - Konversi `ImageProxy` ke `Bitmap`, lalu ke `TensorImage` untuk
     *   kompatibilitas dengan API task library.
     */
    fun detectObject(image: ImageProxy) {

        // Jika library vision belum siap, jangan proses frame ini — segera
        // tutup image agar buffer CameraX tidak penuh.
        if (!TfLiteVision.isInitialized()) {
            image.close()
            return
        }

        // Pastikan detektor sudah tersedia; jika belum ada, coba buat.
        if (objectDetector == null) {
            setupObjectDetector()
        }

        // Jika setelah percobaan masih null, tutup image dan berhenti.
        if (objectDetector == null) {
            image.close()
            return
        }

        // Sesuaikan orientasi gambar agar model menerima input dengan rotasi
        // yang benar; pembagian dengan 90 karena Rot90Op menerima langkah 90 derajat.
        val imageProcessor = ImageProcessor.Builder()
            .add(Rot90Op(-image.imageInfo.rotationDegrees / 90))
            .build()

        // Konversi ImageProxy ke Bitmap (fungsi helper di bawah), lalu ke TensorImage.
        val bitmap = toBitmap(image)
        val tensorImage = imageProcessor.process(TensorImage.fromBitmap(bitmap))

        // Ukur waktu inferensi untuk keperluan debugging/performance UI.
        var inferenceTime = SystemClock.uptimeMillis()
        val results = objectDetector?.detect(tensorImage)
        inferenceTime = SystemClock.uptimeMillis() - inferenceTime
        detectorListener?.onResults(
            results,
            inferenceTime,
            tensorImage.height,
            tensorImage.width,
        )
    }

    /**
     * Konversi sederhana dari `ImageProxy` ke `Bitmap`.
     *
     * **Mengapa cara ini dipilih:**
     * - Kode menyalin langsung buffer piksel dari `ImageProxy` ke `Bitmap` agar
     *   format RGBA dapat langsung diproses. Ini efisien dan menghindari langkah
     *   tambahan jika output CameraX sudah dalam format yang cocok.
     *
     * ⚠️ NOTE: Pendekatan ini mengasumsikan bahwa `image.planes[0].buffer` berisi
     * susunan piksel yang kompatibel dengan `copyPixelsFromBuffer`. Jika CameraX
     * mengeluarkan format lain, diperlukan konversi yang lebih lengkap.
     */
    private fun toBitmap(image: ImageProxy): Bitmap {
        val bitmapBuffer = createBitmap(image.width, image.height)
        // Gunakan `use` untuk memastikan resource ImageProxy ditutup setelah
        // penyalinan buffer sehingga tidak terjadi kebocoran buffer CameraX.
        image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
        return bitmapBuffer
    }

    interface DetectorListener {
        fun onError(error: String)
        fun onResults(
            results: MutableList<Detection>?,
            inferenceTime: Long,
            imageHeight: Int,
            imageWidth: Int,
        )
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}

