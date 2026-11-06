package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stops",
    primaryKeys = ["feedId", "stopId"],
    indices = [Index(value = ["feedId", "stopName"])]
)
data class StopEntity(
    val feedId: String,
    val stopId: String,
    val stopCode: String?,
    val stopName: String,
    val stopDesc: String?,
    val stopLat: Double?,
    val stopLon: Double?,
    val zoneId: String?,
    val locationType: Int?,
    val parentStation: String?,
    val wheelchairBoarding: Int?
)