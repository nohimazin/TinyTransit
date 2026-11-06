package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.FeedEntity

@Dao
interface FeedDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(feed: FeedEntity)

    @Query("SELECT * FROM feeds WHERE feedId = :feedId")
    suspend fun get(feedId: String): FeedEntity?

    @Query("SELECT * FROM feeds")
    suspend fun getAll(): List<FeedEntity>

    @Query("UPDATE feeds SET lastStaticUpdate = :timestamp WHERE feedId = :feedId")
    suspend fun updateLastStaticUpdate(feedId: String, timestamp: Long)

    @Query("UPDATE feeds SET lastRealtimeUpdate = :timestamp WHERE feedId = :feedId")
    suspend fun updateLastRealtimeUpdate(feedId: String, timestamp: Long)
}