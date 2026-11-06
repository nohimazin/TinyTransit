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
        ).build()
    }

    val okHttpClient: OkHttpClient by lazy { OkHttpClient() }

    val gtfsRepository: GtfsRepository by lazy {
        GtfsRepository(this, database, okHttpClient)
    }
}