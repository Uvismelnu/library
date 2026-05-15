package com.medsearch.rag.di

import android.content.Context
import androidx.room.Room
import com.medsearch.rag.data.local.MedSearchDatabase
import com.medsearch.rag.data.local.dao.BookDao
import com.medsearch.rag.data.local.dao.PageChunkDao
import com.medsearch.rag.data.pdf.PdfTextExtractor
import com.medsearch.rag.data.pdf.PdfTextExtractorImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MedSearchDatabase =
        Room.databaseBuilder(
            context,
            MedSearchDatabase::class.java,
            MedSearchDatabase.DB_NAME
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideBookDao(db: MedSearchDatabase): BookDao = db.bookDao()

    @Provides
    fun providePageChunkDao(db: MedSearchDatabase): PageChunkDao = db.pageChunkDao()

    @Provides
    @Singleton
    fun providePdfExtractor(): PdfTextExtractor = PdfTextExtractorImpl()
}
