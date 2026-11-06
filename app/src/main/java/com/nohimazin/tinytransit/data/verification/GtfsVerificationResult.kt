package com.nohimazin.tinytransit.data.verification

data class GtfsVerificationResult(
    val feedId: String,
    val stopCount: Int,
    val routeCount: Int,
    val tripCount: Int,
    val stopTimeCount: Int,
    val calendarCount: Int,
    val calendarDateCount: Int,
    val orphanTripsWithoutRoute: Int,
    val orphanStopTimesWithoutTrip: Int,
    val orphanStopTimesWithoutStop: Int,
    val tripsWithoutService: Int,
    val isConsistent: Boolean
) {
    override fun toString(): String = buildString {
        appendLine("=== GTFS Verification: $feedId ===")
        appendLine("stops: $stopCount, routes: $routeCount, trips: $tripCount")
        appendLine("stop_times: $stopTimeCount, calendar: $calendarCount, calendar_dates: $calendarDateCount")
        appendLine("orphan trips (no route): $orphanTripsWithoutRoute")
        appendLine("orphan stop_times (no trip): $orphanStopTimesWithoutTrip")
        appendLine("orphan stop_times (no stop): $orphanStopTimesWithoutStop")
        appendLine("trips without service: $tripsWithoutService")
        appendLine("consistent: $isConsistent")
    }
}