package com.dicoding.picodiploma.mycamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.tensorflow.lite.task.gms.vision.detector.Detection
import java.text.NumberFormat
import java.util.LinkedList
import kotlin.math.max

/**
 * View overlay yang menggambar bounding box dan label hasil deteksi di atas
 * preview kamera.
 *
 * **Mengapa terpisah:**
 * - Mengisolasi logika penggambaran (drawing) dari Activity agar rendering
 *   tidak bercampur dengan alur data deteksi.
 * - Memungkinkan pembaruan overlay secara terpisah (mis. `setResults` / `clear`).
 *
 * **Catatan:**
 * - Pastikan `setResults` dipanggil setelah view memiliki ukuran (width/height)
 *   karena `scaleFactor` dihitung berdasarkan ukuran view dan ukuran image.
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var boxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    // Menyimpan hasil deteksi yang akan digambar.
    private var results: List<Detection> = LinkedList<Detection>()
    // Faktor skala untuk mentransformasikan koordinat kotak dari ukuran
    // gambar (imageWidth/imageHeight) ke ukuran view (width/height).
    private var scaleFactor: Float = 1f

    private var bounds = Rect()

    init {
        initPaints()
    }

    /**
     * Diberi hasil deteksi dari Activity/Helper dan menghitung faktor skala agar
     * bounding box bisa dipetakan ke koordinat view.
     *
     * **Mengapa melakukan scaling di sini:**
     * - Camera frame dan ukuran view sering berbeda; perlu transformasi untuk
     *   menampilkan koordinat yang benar.
     * - Menggunakan `max` antara rasio lebar dan tinggi menjaga aspek agar box
     *   tetap proporsional dan tidak terdistorsi.
     *
     * ⚠️ NOTE: Jika `width` atau `height` view masih 0 (mis. jika dipanggil
     * sebelum layout selesai), scaleFactor akan menjadi 0 atau NaN — oleh sebab
     * itu panggilan ke `setResults` idealnya dilakukan setelah layout siap.
     */
    fun setResults(
        detectionResults: MutableList<Detection>,
        imageHeight: Int,
        imageWidth: Int,
    ) {
        results = detectionResults
        // Hitung faktor skala berdasarkan rasio ukuran view terhadap ukuran image.
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
    }

    private fun initPaints() {
        // Warna dan gaya untuk bounding box.
        boxPaint.color = ContextCompat.getColor(context, R.color.bounding_box_color)
        boxPaint.style = Paint.Style.STROKE
        boxPaint.strokeWidth = 8f

        // Latar teks label agar tetap terbaca di atas preview kamera.
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        // Warna dan ukuran teks label.
        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f
    }

    /**
     * Menggambar bounding box dan label untuk setiap hasil deteksi.
     *
     * **Rangka alasan gambar seperti ini:**
     * - Pertama gambar kotak agar label tidak menutupi detail box.
     * - Berikan latar belakang gelap pada teks agar tetap terbaca di berbagai
     *   kondisi pencahayaan preview kamera.
     */
    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        for (result in results) {
            val boundingBox = result.boundingBox

            // Skalakan koordinat box dari koordinat image ke koordinat view.
            val left = boundingBox.left * scaleFactor
            val top = boundingBox.top * scaleFactor
            val right = boundingBox.right * scaleFactor
            val bottom = boundingBox.bottom * scaleFactor

            val drawableRect = RectF(left, top, right, bottom)
            // Gambar kotak di sekitar objek.
            canvas.drawRect(drawableRect, boxPaint)

            // Susun teks label + persentase confidence.
            val drawableText =
                "${result.categories[0].label}" + NumberFormat.getPercentInstance().format(result.categories[0].score)

            // Hitung ukuran teks untuk menggambar latar belakangnya.
            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(
                left,
                top,
                left + textWidth + Companion.BOUNDING_RECT_TEXT_PADDING,
                top + textHeight + Companion.BOUNDING_RECT_TEXT_PADDING,
                textBackgroundPaint
            )

            // Gambar teks label di atas latar yang telah dibuat.
            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
        }
    }

    /**
     * Membersihkan overlay dan mengembalikan paint ke keadaan awal.
     *
     * **Mengapa reset paint:**
     * - Memastikan state grafis kembali ke kondisi default sebelum menggambar
     *   ulang; ini berguna jika style pernah dimodifikasi sebelumnya.
     */
    fun clear() {
        boxPaint.reset()
        textBackgroundPaint.reset()
        textPaint.reset()
        invalidate()
        initPaints()
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}