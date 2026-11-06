package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.StopTimeEntity

@Dao
interface StopTimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(stopTimes: List<StopTimeEntity>)

    // バルクインポート用：deleteByFeed 後の挿入のため競合なし。IGNORE の方が高速。
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(stopTimes: List<StopTimeEntity>)

    @Query("SELECT * FROM stop_times WHERE feedId = :feedId AND tripId = :tripId ORDER BY stopSequence ASC")
    suspend fun getByTrip(feedId: String, tripId: String): List<StopTimeEntity>

    @Query("SELECT * FROM stop_times WHERE feedId = :feedId AND stopId = :stopId")
    suspend fun getByStop(feedId: String, stopId: String): List<StopTimeEntity>

    @Query("DELETE FROM stop_times WHERE feedId = :feedId")
    suspend fun deleteByFeed(feedId: String)
}