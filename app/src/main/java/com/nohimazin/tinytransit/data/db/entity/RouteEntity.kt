package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "routes",
    primaryKeys = ["feedId", "routeId"],
    indices = [Index(value = ["feedId", "routeShortName"])]
)
data class RouteEntity(
    val feedId: String,
    val routeId: String,
    val agencyId: String?,
    val routeShortName: String?,
    val routeLongName: String?,
    val routeDesc: String?,
    val routeType: Int?,
    val routeUrl: String?,
    val routeColor: String?,
    val routeTextColor: String?,
    val jpParentRouteId: String?
)