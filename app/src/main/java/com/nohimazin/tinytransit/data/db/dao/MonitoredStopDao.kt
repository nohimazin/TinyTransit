package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.MonitoredStopEntity

@Dao
interface MonitoredStopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stop: MonitoredStopEntity)

    @Delete
    suspend fun delete(stop: MonitoredStopEntity)

    @Query("SELECT * FROM monitored_stops WHERE feedId = :feedId")
    suspend fun getAll(feedId: String): List<MonitoredStopEntity>

    @Query("SELECT * FROM monitored_stops WHERE feedId = :feedId AND stopId = :stopId")
    suspend fun get(feedId: String, stopId: String): MonitoredStopEntity?

    @Query("DELETE FROM monitored_stops WHERE feedId = :feedId AND stopId = :stopId")
    suspend fun deleteByStop(feedId: String, stopId: String)

    @Query("SELECT COUNT(*) FROM monitored_stops WHERE feedId = :feedId")
    suspend fun count(feedId: String): Int
}