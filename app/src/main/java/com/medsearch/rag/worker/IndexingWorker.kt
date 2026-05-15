package com.medsearch.rag.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.medsearch.rag.R
import com.medsearch.rag.data.indexing.IndexingService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val indexingService: IndexingService
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_FOLDER_URI = "folder_uri"
        const val KEY_ALLOW_OCR = "allow_ocr"
        const val CHANNEL_ID = "indexing_channel"
        const val NOTIF_ID = 4421
        const val UNIQUE_NAME = "medsearch_indexing"
    }

    override suspend fun doWork(): Result {
        val uriString = inputData.getString(KEY_FOLDER_URI) ?: return Result.failure()
        val allowOcr = inputData.getBoolean(KEY_ALLOW_OCR, false)
        
        try {
            setForeground(buildForegroundInfo())
        } catch (e: Exception) {
            // En Android 14+, si la app está en background al iniciar, setForeground puede fallar.
            // Ignoramos y continuamos como worker normal para evitar el crash.
            android.util.Log.w("IndexingWorker", "No se pudo iniciar en primer plano", e)
        }

        return runCatching {
            indexingService.indexFolder(applicationContext, Uri.parse(uriString), allowOcr)
            Result.success()
        }.getOrElse { 
            android.util.Log.e("IndexingWorker", "Error fatal en indexación", it)
            Result.failure() 
        }
    }

    private fun buildForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Indexación de biblioteca",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Progreso de indexación de PDFs médicos" }
            nm.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.indexing_in_progress))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }
}
