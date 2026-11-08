package com.nohimazin.tinytransit

import android.app.Application
import com.nohimazin.tinytransit.data.db.TinyTransitDatabase
import com.nohimazin.tinytransit.data.repository.GtfsRepository
import okhttp3.OkHttpClient

class TinyTransitApp : Application() {
    val database: TinyTransitDatabase by lazy {
        androidx.room.Room.databaseBuilder(
            this,
            TinyTransitDatabase::class.java,
            "tinytransit.db"
        ).fallbackToDestructiveMigration().build()
    }

    val okHttpClient: OkHttpClient by lazy { OkHttpClient() }

    // リアルタイム取得専用クライアント (タイムアウト明示)
    val realtimeHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    val gtfsRepository: GtfsRepository by lazy {
        GtfsRepository(this, database, okHttpClient)
    }
}