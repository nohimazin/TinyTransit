package com.nohimazin.tinytransit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nohimazin.tinytransit.data.db.entity.CalendarDateEntity
import com.nohimazin.tinytransit.data.db.entity.CalendarEntity

@Dao
interface CalendarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCalendar(calendar: List<CalendarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCalendarDates(dates: List<CalendarDateEntity>)

    @Query("SELECT * FROM calendar WHERE feedId = :feedId AND serviceId = :serviceId")
    suspend fun getCalendar(feedId: String, serviceId: String): CalendarEntity?

    @Query("SELECT * FROM calendar_dates WHERE feedId = :feedId AND serviceId = :serviceId")
    suspend fun getCalendarDates(feedId: String, serviceId: String): List<CalendarDateEntity>

    @Query("DELETE FROM calendar WHERE feedId = :feedId")
    suspend fun deleteCalendarByFeed(feedId: String)

    @Query("DELETE FROM calendar_dates WHERE feedId = :feedId")
    suspend fun deleteCalendarDatesByFeed(feedId: String)
}