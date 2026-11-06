package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_dates",
    primaryKeys = ["feedId", "serviceId", "date"],
    indices = [Index(value = ["feedId", "serviceId"])]
)
data class CalendarDateEntity(
    val feedId: String,
    val serviceId: String,
    val date: String,
    val exceptionType: Int
)