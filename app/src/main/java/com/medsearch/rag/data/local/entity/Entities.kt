package com.medsearch.rag.data.local.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un libro PDF indexado. El campo `uri` es la URI persistente del documento
 * obtenida vía Storage Access Framework.
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val totalPages: Int,
    val indexedPages: Int,
    val sizeBytes: Long,
    val lastIndexedAt: Long,
    val isOcr: Boolean = false
)

/**
 * Un "chunk" indexable. Para libros con muchas páginas dividimos por página,
 * y si una página es demasiado grande la subdividimos en bloques de ~1500 chars.
 */
@Entity(
    tableName = "page_chunks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("pageNumber")]
)
data class PageChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageNumber: Int,
    val chunkIndex: Int,
    val text: String
)

/**
 * Tabla FTS4 sombra de page_chunks. Room conecta automáticamente
 * mediante rowid <-> id si declaramos `contentEntity = PageChunkEntity::class`.
 *
 * Usamos unicode61 con remove_diacritics=2 para que "fibrilacion" matchee con
 * "fibrilación" (crítico en español médico).
 */
@Fts4(
    contentEntity = PageChunkEntity::class,
    tokenizer = "unicode61",
    tokenizerArgs = ["remove_diacritics=2"]
)
@Entity(tableName = "page_chunks_fts")
data class PageChunkFts(
    val text: String
)
