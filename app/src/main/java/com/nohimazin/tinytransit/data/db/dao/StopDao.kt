package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.StopEntity

@Dao
interface StopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stops: List<StopEntity>)

    @Query("SELECT * FROM stops WHERE feedId = :feedId AND stopId = :stopId")
    suspend fun get(feedId: String, stopId: String): StopEntity?

    @Query("SELECT * FROM stops WHERE feedId = :feedId AND stopName LIKE '%' || :query || '%' ORDER BY stopName LIMIT :limit")
    suspend fun searchByName(feedId: String, query: String, limit: Int = 50): List<StopEntity>

    @Query("SELECT COUNT(*) FROM stops WHERE feedId = :feedId")
    suspend fun count(feedId: String): Int

    @Query("SELECT * FROM stops WHERE feedId = :feedId")
    suspend fun getAll(feedId: String): List<StopEntity>

    @Query("DELETE FROM stops WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: String)
}