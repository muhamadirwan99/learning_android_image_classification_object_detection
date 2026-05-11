# Image Classification Object Detection

Aplikasi Android sederhana untuk menampilkan preview kamera dan melakukan deteksi objek secara real-time menggunakan CameraX dan TensorFlow Lite (Object Detection Task). Aplikasi ini menampilkan bounding box dan label pada objek yang terdeteksi serta durasi inferensi (inference time).

---

## Fitur Utama

- Real-time object detection dari camera preview (CameraX + ImageAnalysis)
- Overlay bounding box dan label (custom `OverlayView`) di atas preview
- Menampilkan waktu inferensi (ms) untuk setiap frame yang diproses
- Dukungan GPU delegate (jika perangkat mendukung) untuk mempercepat inferensi
- Penanganan orientasi kamera (rotasi frame sebelum inferensi)


## Teknologi & Library

- Bahasa: Kotlin
- UI binding: ViewBinding (`ActivityCameraBinding`)
- Camera: AndroidX CameraX (Preview, ImageAnalysis)
- Machine Learning: TensorFlow Lite Task Library (ObjectDetector)
- Utilitas: AndroidX, Kotlin stdlib
- Arsitektur: pemisahan tanggung jawab antara Activity (UI/Cameras) dan Helper (deteksi)


## Pelajaran Penting (Key Takeaways)

Berikut beberapa konsep Android / ML yang dipelajari dan relevan dengan proyek ini:

1. CameraX ImageAnalysis & Backpressure
   - Gunakan strategi `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST` untuk mencegah antrean frame menumpuk ketika processing lambat.
   - Alasan: inferensi sering lebih lambat daripada kecepatan frame kamera, sehingga memproses semua frame menyebabkan lag dan konsumsi memori.

2. Penanganan orientasi (Rotation) sebelum inferensi
   - Metadata rotasi dari CameraX harus diterapkan (mis. rotasi 90° steps) agar input ke model memiliki orientasi yang benar. Di proyek ini dilakukan lewat `Rot90Op` sebelum convert ke `TensorImage`.
   - Alasan: model dilatih/dioptimalkan untuk orientasi tertentu; jika tidak disesuaikan, hasil prediksi akan salah.

3. Inisialisasi TensorFlow Lite & GPU Delegate
   - Inisialisasi `TfLiteVision` dilakukan asinkron dan dicek ketersediaan GPU delegate. Meminta penggunaan GPU tidak selalu menjamin GPU akan dipakai; tergantung kompatibilitas perangkat dan model.
   - Alasan: delegate GPU dapat mempercepat inferensi tetapi perlu penanganan fallback apabila tidak tersedia.

4. Lifecycle dan resource handling (`ImageProxy`)
   - Penting untuk menutup (`image.close()` atau menggunakan `use`) setiap `ImageProxy` setelah selesai memproses, agar buffer CameraX tidak bocor dan kamera tidak macet.
   - Alasan: `ImageAnalysis` memberi buffer terbatas; kalau tidak ditutup, pipeline akan berhenti menerima frame baru.

Optional / Tambahan (konsep Android yang sering muncul ketika menambahkan fitur lain):
- Perbedaan `ELAPSED_REALTIME` vs `RTC` untuk AlarmManager — gunakan `ELAPSED_REALTIME` untuk menghitung interval berdasarkan waktu jalannya perangkat, dan `RTC` jika ingin alarm berdasarkan waktu dinding (UTC/local).
- Flags pada `PendingIntent` (mis. `FLAG_UPDATE_CURRENT`, `FLAG_IMMUTABLE`): _⚠️ penting ketika menargetkan Android 12+ (API 31+), `FLAG_IMMUTABLE` atau `FLAG_MUTABLE` sering diwajibkan._


## Cara Setup (Singkat)

1. Pastikan Anda memiliki Android Studio (recommend versi terbaru) dan Android SDK yang sesuai.
2. Clone repository ini ke mesin Anda.
3. Buka project di Android Studio.
4. Sync Gradle / tunggu dependencies terunduh.
5. Pastikan model TFLite (`efficientdet_lite0_v1.tflite`) ada di folder `app/src/main/assets/` atau lokasi yang sesuai.
6. Jalankan aplikasi pada perangkat fisik (direkomendasikan) atau emulator dengan dukungan CameraX.

Perintah singkat build & run dari Android Studio: Build -> Make Project, lalu Run.


## Screenshots

Silakan ganti placeholder berikut dengan screenshot atau GIF aplikasi Anda:

- Preview + Deteksi:

![Screenshot Preview](url_gambar_preview)

- Overlay dan Inference Time:

![Screenshot Overlay](url_gambar_overlay)


---

Jika Anda ingin, saya dapat membantu:
- Menambahkan file `assets/` model jika Anda berikan file .tflite
- Mengubah literal string UI menjadi resources (strings.xml) untuk memperbaiki peringatan lint
- Menambahkan instruksi lebih rinci untuk optimasi performa (quantization, NNAPI, dll.)

Terima kasih — beri tahu kalau mau revisi gaya bahasa, tambahan bagian teknis, atau template README versi bahasa Inggris juga.

