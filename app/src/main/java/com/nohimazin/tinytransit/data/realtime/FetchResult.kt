package com.nohimazin.tinytransit.data.realtime

import com.google.transit.realtime.GtfsRealtime

/**
 * HTTP 取得結果を表す sealed class。
 * 通信量を記録するため、各結果に receivedBytes を持つ。
 */
sealed class FetchResult {
    /** 取得成功 */
    data class Success(
        val receivedBytes: Long,
        val feedMessage: GtfsRealtime.FeedMessage,
        val predictions: List<ArrivalPrediction>
    ) : FetchResult()

    /** HTTP エラー (4xx, 5xx 等) */
    data class HttpError(
        val statusCode: Int,
        val receivedBytes: Long,
        val retryAfterMs: Long? = null
    ) : FetchResult()

    /** ネットワークエラー (接続失敗等) */
    data class NetworkError(
        val exception: Exception
    ) : FetchResult()

    /** タイムアウト */
    data class Timeout(
        val exception: Exception
    ) : FetchResult()

    /** Protobuf デコードエラー */
    data class DecodeError(
        val exception: Exception,
        val receivedBytes: Long
    ) : FetchResult()
}

enum class FetchErrorType {
    NONE,
    HTTP_ERROR,
    NETWORK_ERROR,
    TIMEOUT,
    DECODE_ERROR
}