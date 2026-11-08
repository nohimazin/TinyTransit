package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ユーザーが監視対象として追加した停留所。
 * 設定画面で追加し、トップ画面に遅延情報を表示する。
 */
@Entity(
    tableName = "monitored_stops",
    primaryKeys = ["feedId", "stopId"],
    indices = [Index(value = ["feedId"])]
)
data class MonitoredStopEntity(
    val feedId: String,
    val stopId: String,
    val stopName: String,
    val routeId: String? = null,
    val routeName: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)