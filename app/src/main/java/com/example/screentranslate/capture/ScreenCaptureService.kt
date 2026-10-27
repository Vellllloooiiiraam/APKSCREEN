package com.example.screentranslate.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.IntentCompat
import com.example.screentranslate.R
import com.example.screentranslate.core.LogBus

/**
 * Um frame pronto para OCR. O bitmap esta reduzido, por isso carrega tambem os
 * fatores de escala que convertem pixels do bitmap em pixels da tela.
 */
data class CapturedFrame(
    val bitmap: Bitmap,
    val scaleX: Float,
    val scaleY: Float,
    /** Incrementa a cada novo frame: permite pular OCR quando a tela nao mudou. */
    val id: Long
)

/**
 * Foreground service que mantem viva uma sessao de MediaProjection e guarda
 * sempre o frame mais recente da tela (reduzido) pronto para o OCR.
 *
 * No Android 14+ o servico precisa estar rodando com
 * foregroundServiceType=mediaProjection ANTES de chamar getMediaProjection().
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    @Volatile
    private var lastFrame: Bitmap? = null

    @Volatile
    private var frameScaleX: Float = 1f

    @Volatile
    private var frameScaleY: Float = 1f

    @Volatile
    private var frameId: Long = 0L

    @Volatile
    private var screenWidthPx: Int = 0

    @Volatile
    private var screenHeightPx: Int = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            LogBus.log("Captura", "projeção encerrada pelo sistema")
            releaseResources()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        if (projection == null && intent != null) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_UNKNOWN)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
            if (data != null) {
                startProjection(resultCode, data)
            } else {
                LogBus.log("Captura", "sem Intent de autorização — captura não iniciada")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseResources()
        instance = null
        LogBus.log("Captura", "serviço encerrado")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun startAsForeground() {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notif_title))
            .setContentText(getString(R.string.capture_notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_notif_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.capture_notif_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // ---------------------------------------------------------------- projection

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = try {
            manager.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            LogBus.log("Captura", "getMediaProjection falhou: ${t.message}")
            null
        }
        if (mp == null) {
            LogBus.log("Captura", "projeção indisponível")
            return
        }

        val metrics = currentMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        screenWidthPx = width
        screenHeightPx = height

        val ht = HandlerThread("screen-capture")
        ht.start()
        thread = ht
        val handler = Handler(ht.looper)
        worker = handler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)
        imageReader = reader

        mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
        projection = mp

        virtualDisplay = mp.createVirtualDisplay(
            "ScreenTranslateCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
        LogBus.log("Captura", "ativa em ${width}x$height @ ${density}dpi")
    }

    private fun currentMetrics(): DisplayMetrics {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            dm.widthPixels = bounds.width()
            dm.heightPixels = bounds.height()
            dm.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
        }
        return dm
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image: Image = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            null
        } ?: return

        try {
            val full = image.toBitmap()
            val scaled = if (full.width > MAX_WIDTH) {
                val targetHeight =
                    (full.height * (MAX_WIDTH.toFloat() / full.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(full, MAX_WIDTH, targetHeight, true).also { full.recycle() }
            } else {
                full
            }
            val screenWidth = screenWidthPx.takeIf { it > 0 } ?: scaled.width
            val screenHeight = screenHeightPx.takeIf { it > 0 } ?: scaled.height

            lastFrame = scaled
            frameScaleX = screenWidth.toFloat() / scaled.width
            frameScaleY = screenHeight.toFloat() / scaled.height
            frameId += 1
        } catch (t: Throwable) {
            LogBus.log("Captura", "frame descartado: ${t.message}")
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
                // ignora
            }
        }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun releaseResources() {
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
            // ignora
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (_: Throwable) {
            // ignora
        }
        imageReader = null

        val current = projection
        projection = null
        if (current != null) {
            try {
                current.unregisterCallback(projectionCallback)
            } catch (_: Throwable) {
                // ignora
            }
            try {
                current.stop()
            } catch (_: Throwable) {
                // ignora
            }
        }

        thread?.quitSafely()
        thread = null
        worker = null
    }

    companion object {
        private const val ACTION_START = "com.example.screentranslate.action.START_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_translate_capture"
        private const val NOTIFICATION_ID = 4711
        private const val MAX_WIDTH = 900
        private const val RESULT_UNKNOWN = -1

        @Volatile
        private var instance: ScreenCaptureService? = null

        val isRunning: Boolean get() = instance != null

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        /**
         * Ultimo frame capturado, ou null enquanto a tela nao produzir nenhum.
         * Nunca reciclamos o bitmap: o OCR pode estar lendo enquanto um novo
         * frame chega, e reciclar causaria use-after-recycle.
         */
        fun grabFrame(): CapturedFrame? {
            val service = instance ?: return null
            val frame = service.lastFrame ?: return null
            if (frame.isRecycled) return null
            return CapturedFrame(
                bitmap = frame,
                scaleX = service.frameScaleX,
                scaleY = service.frameScaleY,
                id = service.frameId
            )
        }
    }
}