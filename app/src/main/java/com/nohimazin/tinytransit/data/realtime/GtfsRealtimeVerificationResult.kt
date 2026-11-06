package com.nohimazin.tinytransit.data.realtime

/**
 * GTFS-Realtime と静的 GTFS の照合検証結果。
 */
data class GtfsRealtimeVerificationResult(
    val feedId: String,
    val tripUpdateCount: Int,
    val stopTimeUpdateCount: Int,
    val tripIdMatched: Int,
    val tripIdUnmatched: Int,
    val stopMatched: Int,
    val stopSequenceMismatch: Int,
    val stopIdMismatch: Int,
    val stopUnmatched: Int,
    val stopBothConsistent: Int,
    val stopBothInconsistent: Int,
    val skippedCount: Int,
    val canceledCount: Int,
    val predictedTimeValid: Int,
    val predictedTimeInvalid: Int,
    val isConsistent: Boolean
) {
    override fun toString(): String = buildString {
        appendLine("=== GTFS-Realtime Verification: $feedId ===")
        appendLine("TripUpdate: $tripUpdateCount, StopTimeUpdate: $stopTimeUpdateCount")
        appendLine("trip_id: matched=$tripIdMatched, unmatched=$tripIdUnmatched")
        appendLine("stop: matched=$stopMatched, seqMismatch=$stopSequenceMismatch, idMismatch=$stopIdMismatch, unmatched=$stopUnmatched")
        appendLine("both consistent=$stopBothConsistent, both inconsistent=$stopBothInconsistent")
        appendLine("skipped=$skippedCount, canceled=$canceledCount")
        appendLine("predicted time: valid=$predictedTimeValid, invalid=$predictedTimeInvalid")
        appendLine("consistent: $isConsistent")
    }
}