package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.TripEntity

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(trips: List<TripEntity>)

    @Query("SELECT * FROM trips WHERE feedId = :feedId AND tripId = :tripId")
    suspend fun get(feedId: String, tripId: String): TripEntity?

    @Query("SELECT * FROM trips WHERE feedId = :feedId AND routeId = :routeId")
    suspend fun getByRoute(feedId: String, routeId: String): List<TripEntity>

    @Query("SELECT * FROM trips WHERE feedId = :feedId")
    suspend fun getAll(feedId: String): List<TripEntity>

    @Query("DELETE FROM trips WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: String)
}