package com.tuempresa.uberassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var windowManager: WindowManager? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val CHANNEL_ID = "ScreenCaptureServiceChannel"
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Asistente Activo")
            .setContentText("Calculando rentabilidad simultánea...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>("RESULT_DATA")

        if (resultCode != 0 && resultData != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            startVirtualDisplay()
        }

        return START_NOT_STICKY
    }

    private fun startVirtualDisplay() {
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                if (bitmap != null) {
                    procesoOCR(bitmap)
                }
            }
        }, null)
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun procesoOCR(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                analizarDatosDeViaje(visionText.text)
            }
            .addOnFailureListener { }
    }

    private fun analizarDatosDeViaje(texto: String) {
        val regexPrecio = Regex("""\$?\s*(\d{1,3}(?:\.\d{3})*(?:,\d+)?|\d+(?:\.\d+)?)""")
        val regexKm = Regex("""(\d+[\.,]?\d*)\s*(?:km|kilómetros)""", RegexOption.IGNORE_CASE)
        val regexMinutos = Regex("""(\d+)\s*(?:min|mins|minutos)""", RegexOption.IGNORE_CASE)

        val matchPrecio = regexPrecio.find(texto)
        val matchKm = regexKm.find(texto)
        val matchMinutos = regexMinutos.find(texto)

        if (matchPrecio != null) {
            val precioStr = matchPrecio.groupValues[1].replace(".", "").replace(",", ".")
            val precio = precioStr.toFloatOrNull() ?: 0f

            val kmStr = matchKm?.groupValues?.get(1)?.replace(",", ".") ?: "0"
            val distanciaKm = kmStr.toFloatOrNull() ?: 0f

            val minStr = matchMinutos?.groupValues?.get(1) ?: "0"
            val tiempoMin = minStr.toFloatOrNull() ?: 0f

            val gananciaPorKm = if (distanciaKm > 0f) precio / distanciaKm else 0f
            val gananciaPorHora = if (tiempoMin > 0f) (precio / tiempoMin) * 60f else 0f

            val minGananciaPorKm = 300.0f
            val minGananciaPorHora = 8000.0f

            val cumpleKm = gananciaPorKm >= minGananciaPorKm
            val cumpleHora = gananciaPorHora >= minGananciaPorHora

            if (cumpleKm && cumpleHora) {
                dispararAlertaParaMacroDroid()
            }
        }
    }

    private fun dispararAlertaParaMacroDroid() {
        val intent = Intent("com.tuempresa.uberassistant.ACCEPAT_TRIP").apply {
            putExtra("ESTADO", "VIAJE_APROBADO")
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal del Asistente",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
