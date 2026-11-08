package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * ユーザーが監視対象として登録した乗車→降車の経路セット。
 * トップ画面に遅延情報を表示する。
 */
@Entity(
    tableName = "monitored_routes",
    primaryKeys = ["feedId", "originStopId", "destStopId"],
    indices = [Index(value = ["feedId"])]
)
data class MonitoredRouteEntity(
    val feedId: String,
    val originStopId: String,
    val originStopName: String,
    val destStopId: String,
    val destStopName: String,
    val addedAt: Long = System.currentTimeMillis()
)