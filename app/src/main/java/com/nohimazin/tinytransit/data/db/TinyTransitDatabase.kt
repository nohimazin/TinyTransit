package com.nohimazin.tinytransit.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nohimazin.tinytransit.data.db.dao.CalendarDao
import com.nohimazin.tinytransit.data.db.dao.FeedDao
import com.nohimazin.tinytransit.data.db.dao.MonitoredRouteDao
import com.nohimazin.tinytransit.data.db.dao.MonitoredStopDao
import com.nohimazin.tinytransit.data.db.dao.RouteDao
import com.nohimazin.tinytransit.data.db.dao.StopDao
import com.nohimazin.tinytransit.data.db.dao.StopTimeDao
import com.nohimazin.tinytransit.data.db.dao.TripDao
import com.nohimazin.tinytransit.data.db.entity.CalendarDateEntity
import com.nohimazin.tinytransit.data.db.entity.CalendarEntity
import com.nohimazin.tinytransit.data.db.entity.FeedEntity
import com.nohimazin.tinytransit.data.db.entity.MonitoredRouteEntity
import com.nohimazin.tinytransit.data.db.entity.MonitoredStopEntity
import com.nohimazin.tinytransit.data.db.entity.RouteEntity
import com.nohimazin.tinytransit.data.db.entity.StopEntity
import com.nohimazin.tinytransit.data.db.entity.StopTimeEntity
import com.nohimazin.tinytransit.data.db.entity.TripEntity

@Database(
    entities = [
        FeedEntity::class,
        StopEntity::class,
        RouteEntity::class,
        TripEntity::class,
        StopTimeEntity::class,
        CalendarEntity::class,
        CalendarDateEntity::class,
        MonitoredStopEntity::class,
        MonitoredRouteEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class TinyTransitDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun stopDao(): StopDao
    abstract fun routeDao(): RouteDao
    abstract fun tripDao(): TripDao
    abstract fun stopTimeDao(): StopTimeDao
    abstract fun calendarDao(): CalendarDao
    abstract fun monitoredStopDao(): MonitoredStopDao
    abstract fun monitoredRouteDao(): MonitoredRouteDao
}