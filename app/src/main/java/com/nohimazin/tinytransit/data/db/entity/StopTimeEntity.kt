package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stop_times",
    primaryKeys = ["feedId", "tripId", "stopSequence"],
    indices = [
        Index(value = ["feedId", "tripId"]),
        Index(value = ["feedId", "stopId"])
    ]
)
data class StopTimeEntity(
    val feedId: String,
    val tripId: String,
    val stopId: String,
    val stopSequence: Int,
    val arrivalTime: String?,
    val departureTime: String?,
    val stopHeadsign: String?,
    val pickupType: Int?,
    val dropOffType: Int?
)