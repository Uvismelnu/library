package com.medsearch.rag

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MedSearchApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // PdfBox-Android necesita inicializarse con el contexto para sus recursos.
        PDFBoxResourceLoader.init(applicationContext)
        
        // Redirigimos los archivos temporales de PDFBox al directorio de cache de la app
        // Esto es vital para procesar archivos grandes (>100MB) sin agotar la RAM.
        System.setProperty("java.io.tmpdir", cacheDir.absolutePath)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
