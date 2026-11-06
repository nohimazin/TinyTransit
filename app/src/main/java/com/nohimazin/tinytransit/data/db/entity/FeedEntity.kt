package com.nohimazin.tinytransit.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 事業者（フィード）情報。
 * 複数事業者に対応するため、すべてのエンティティは feedId で識別されます。
 */
@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey
    val feedId: String,
    val name: String,
    val staticGtfsUrl: String?,
    val tripUpdateUrl: String?,
    val vehiclePositionUrl: String?,
    val alertUrl: String?,
    val lastStaticUpdate: Long = 0L,
    val lastRealtimeUpdate: Long = 0L
)