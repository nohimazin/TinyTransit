package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.RouteEntity

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(routes: List<RouteEntity>)

    @Query("SELECT * FROM routes WHERE feedId = :feedId AND routeId = :routeId")
    suspend fun get(feedId: String, routeId: String): RouteEntity?

    @Query("SELECT * FROM routes WHERE feedId = :feedId")
    suspend fun getAll(feedId: String): List<RouteEntity>

    @Query("DELETE FROM routes WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: String)
}