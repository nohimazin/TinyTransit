package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.MonitoredRouteEntity

@Dao
interface MonitoredRouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: MonitoredRouteEntity)

    @Delete
    suspend fun delete(route: MonitoredRouteEntity)

    @Query("SELECT * FROM monitored_routes WHERE feedId = :feedId")
    suspend fun getAll(feedId: String): List<MonitoredRouteEntity>

    @Query("DELETE FROM monitored_routes WHERE feedId = :feedId AND originStopId = :originStopId AND destStopId = :destStopId")
    suspend fun deleteByRoute(feedId: String, originStopId: String, destStopId: String)

    @Query("SELECT COUNT(*) FROM monitored_routes WHERE feedId = :feedId")
    suspend fun count(feedId: String): Int
}