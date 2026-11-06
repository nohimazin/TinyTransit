package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    primaryKeys = ["feedId", "tripId"],
    indices = [
        Index(value = ["feedId", "routeId"]),
        Index(value = ["feedId", "serviceId"])
    ]
)
data class TripEntity(
    val feedId: String,
    val tripId: String,
    val routeId: String,
    val serviceId: String,
    val tripHeadsign: String?,
    val tripShortName: String?,
    val directionId: Int?,
    val blockId: String?,
    val shapeId: String?,
    val jpOfficeId: String?
)