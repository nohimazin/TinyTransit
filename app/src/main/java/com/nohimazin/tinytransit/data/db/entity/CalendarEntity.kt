package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar",
    primaryKeys = ["feedId", "serviceId"],
    indices = [Index(value = ["feedId", "serviceId"])]
)
data class CalendarEntity(
    val feedId: String,
    val serviceId: String,
    val monday: Int,
    val tuesday: Int,
    val wednesday: Int,
    val thursday: Int,
    val friday: Int,
    val saturday: Int,
    val sunday: Int,
    val startDate: String,
    val endDate: String
)