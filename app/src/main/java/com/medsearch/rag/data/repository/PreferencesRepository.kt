package com.medsearch.rag.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("medsearch_prefs")

@Singleton
class PreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val FOLDER_URI = stringPreferencesKey("folder_uri")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val OCR_ENABLED = booleanPreferencesKey("ocr_enabled")
        val MAX_CHUNKS = intPreferencesKey("max_chunks")
        val DISCLAIMER_ACK = booleanPreferencesKey("disclaimer_ack")
    }

    val folderUri: Flow<String?> = context.dataStore.data.map { it[Keys.FOLDER_URI] }
    val modelPath: Flow<String?> = context.dataStore.data.map { it[Keys.MODEL_PATH] }
    val ocrEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.OCR_ENABLED] ?: false }
    val maxChunksForRag: Flow<Int> = context.dataStore.data.map { it[Keys.MAX_CHUNKS] ?: 6 }
    val disclaimerAcknowledged: Flow<Boolean> = context.dataStore.data.map { it[Keys.DISCLAIMER_ACK] ?: false }

    suspend fun setFolderUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.FOLDER_URI) else it[Keys.FOLDER_URI] = uri
        }
    }

    suspend fun setModelPath(path: String?) {
        context.dataStore.edit {
            if (path == null) it.remove(Keys.MODEL_PATH) else it[Keys.MODEL_PATH] = path
        }
    }

    suspend fun setOcrEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OCR_ENABLED] = enabled }
    }

    suspend fun setMaxChunksForRag(n: Int) {
        context.dataStore.edit { it[Keys.MAX_CHUNKS] = n.coerceIn(2, 12) }
    }

    suspend fun setDisclaimerAcknowledged(value: Boolean) {
        context.dataStore.edit { it[Keys.DISCLAIMER_ACK] = value }
    }
}
