package com.nohimazin.tinytransit.data.gtfs

import com.nohimazin.tinytransit.data.db.entity.CalendarDateEntity
import com.nohimazin.tinytransit.data.db.entity.CalendarEntity
import com.nohimazin.tinytransit.data.db.entity.RouteEntity
import com.nohimazin.tinytransit.data.db.entity.StopEntity
import com.nohimazin.tinytransit.data.db.entity.StopTimeEntity
import com.nohimazin.tinytransit.data.db.entity.TripEntity

/**
 * GTFS CSV をエンティティに変換するパーサー。
 * 1 つのフィード（feedId）分のデータを処理します。
 */
object GtfsParser {

    fun parseStops(feedId: String, csvText: String): List<StopEntity> {
        return GtfsCsvReader.parse(csvText).map { row ->
            StopEntity(
                feedId = feedId,
                stopId = row["stop_id"] ?: "",
                stopCode = row["stop_code"]?.takeIf { it.isNotEmpty() },
                stopName = row["stop_name"] ?: "",
                stopDesc = row["stop_desc"]?.takeIf { it.isNotEmpty() },
                stopLat = row["stop_lat"]?.toDoubleOrNull(),
                stopLon = row["stop_lon"]?.toDoubleOrNull(),
                zoneId = row["zone_id"]?.takeIf { it.isNotEmpty() },
                locationType = row["location_type"]?.toIntOrNull(),
                parentStation = row["parent_station"]?.takeIf { it.isNotEmpty() },
                wheelchairBoarding = row["wheelchair_boarding"]?.toIntOrNull()
            )
        }
    }

    fun parseRoutes(feedId: String, csvText: String): List<RouteEntity> {
        return GtfsCsvReader.parse(csvText).map { row ->
            RouteEntity(
                feedId = feedId,
                routeId = row["route_id"] ?: "",
                agencyId = row["agency_id"]?.takeIf { it.isNotEmpty() },
                routeShortName = row["route_short_name"]?.takeIf { it.isNotEmpty() },
                routeLongName = row["route_long_name"]?.takeIf { it.isNotEmpty() },
                routeDesc = row["route_desc"]?.takeIf { it.isNotEmpty() },
                routeType = row["route_type"]?.toIntOrNull(),
                routeUrl = row["route_url"]?.takeIf { it.isNotEmpty() },
                routeColor = row["route_color"]?.takeIf { it.isNotEmpty() },
                routeTextColor = row["route_text_color"]?.takeIf { it.isNotEmpty() },
                jpParentRouteId = row["jp_parent_route_id"]?.takeIf { it.isNotEmpty() }
            )
        }
    }

    fun parseTrips(feedId: String, csvText: String): List<TripEntity> {
        return GtfsCsvReader.parse(csvText).map { row ->
            TripEntity(
                feedId = feedId,
                tripId = row["trip_id"] ?: "",
                routeId = row["route_id"] ?: "",
                serviceId = row["service_id"] ?: "",
                tripHeadsign = row["trip_headsign"]?.takeIf { it.isNotEmpty() },
                tripShortName = row["trip_short_name"]?.takeIf { it.isNotEmpty() },
                directionId = row["direction_id"]?.toIntOrNull(),
                blockId = row["block_id"]?.takeIf { it.isNotEmpty() },
                shapeId = row["shape_id"]?.takeIf { it.isNotEmpty() },
                jpOfficeId = row["jp_office_id"]?.takeIf { it.isNotEmpty() }
            )
        }
    }

    /**
     * 1 行分のフィールドとヘッダーから StopTimeEntity を生成します。
     * ストリーミングパース用。
     */
    fun parseStopTimeRow(feedId: String, fields: List<String>, header: List<String>): StopTimeEntity? {
        val map = header.mapIndexed { i, name -> name to (fields.getOrElse(i) { "" }) }.toMap()
        val tripId = map["trip_id"] ?: return null
        if (tripId.isEmpty()) return null
        return StopTimeEntity(
            feedId = feedId,
            tripId = tripId,
            stopId = map["stop_id"] ?: "",
            stopSequence = map["stop_sequence"]?.toIntOrNull() ?: 0,
            arrivalTime = map["arrival_time"]?.takeIf { it.isNotEmpty() },
            departureTime = map["departure_time"]?.takeIf { it.isNotEmpty() },
            stopHeadsign = map["stop_headsign"]?.takeIf { it.isNotEmpty() },
            pickupType = map["pickup_type"]?.toIntOrNull(),
            dropOffType = map["drop_off_type"]?.toIntOrNull()
        )
    }

    fun parseStopTimes(feedId: String, csvText: String): List<StopTimeEntity> {
        return GtfsCsvReader.parse(csvText).map { row ->
            StopTimeEntity(
                feedId = feedId,
                tripId = row["trip_id"] ?: "",
                stopId = row["stop_id"] ?: "",
                stopSequence = row["stop_sequence"]?.toIntOrNull() ?: 0,
                arrivalTime = row["arrival_time"]?.takeIf { it.isNotEmpty() },
                departureTime = row["departure_time"]?.takeIf { it.isNotEmpty() },
                stopHeadsign = row["stop_headsign"]?.takeIf { it.isNotEmpty() },
                pickupType = row["pickup_type"]?.toIntOrNull(),
                dropOffType = row["drop_off_type"]?.toIntOrNull()
            )
        }
    }

    fun parseCalendar(feedId: String, csvText: String): List<CalendarEntity> {
        return GtfsCsvReader.parse(csvText).map { row ->
            CalendarEntity(
                feedId = feedId,
                serviceId = row["service_id"] ?: "",
                monday = row["monday"]?.toIntOrNull() ?: 0,
                tuesday = row["tuesday"]?.toIntOrNull() ?: 0,
                wednesday = row["wednesday"]?.toIntOrNull() ?: 0,
                thursday = row["thursday"]?.toIntOrNull() ?: 0,
                friday = row["friday"]?.toIntOrNull() ?: 0,
                saturday = row["saturday"]?.toIntOrNull() ?: 0,
                sunday = row["sunday"]?.toIntOrNull() ?: 0,
                startDate = row["start_date"] ?: "",
                endDate = row["end_date"] ?: ""
            )
        }
    }

    fun parseCalendarDates(feedId: String, csvText: String): List<CalendarDateEntity> {
        return GtfsCsvReader.parse(csvText).map { row ->
            CalendarDateEntity(
                feedId = feedId,
                serviceId = row["service_id"] ?: "",
                date = row["date"] ?: "",
                exceptionType = row["exception_type"]?.toIntOrNull() ?: 0
            )
        }
    }
}