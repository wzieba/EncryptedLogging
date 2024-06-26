package com.automattic.encryptedlogging.network.rest.wpcom.encryptedlog

import com.android.volley.NoConnectionError
import com.android.volley.RequestQueue
import com.android.volley.VolleyError
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONException
import org.json.JSONObject
import com.automattic.encryptedlogging.network.EncryptedLogUploadRequest
import com.automattic.encryptedlogging.network.rest.wpcom.encryptedlog.UploadEncryptedLogResult.LogUploadFailed
import com.automattic.encryptedlogging.network.rest.wpcom.encryptedlog.UploadEncryptedLogResult.LogUploaded
import com.automattic.encryptedlogging.store.UploadEncryptedLogError
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.AppLog.T.API
import kotlin.coroutines.resume

private const val INVALID_REQUEST = "invalid-request"
private const val TOO_MANY_REQUESTS = "too_many_requests"

internal class EncryptedLogRestClient(
    private val requestQueue: RequestQueue,
    private val clientSecret: String,
) {
    suspend fun uploadLog(logUuid: String, contents: String): UploadEncryptedLogResult {
        return suspendCancellableCoroutine { cont ->
            val request = EncryptedLogUploadRequest(logUuid, contents, clientSecret, {
                cont.resume(LogUploaded)
            }, { error ->
                cont.resume(LogUploadFailed(mapError(error)))
            })
            cont.invokeOnCancellation { request.cancel() }
            requestQueue.add(request)
        }
    }

    /**
     * {
     *   "error":"too_many_requests",
     *   "message":"You're sending too many messages. Please slow down."
     * }
     * {
     *   "error":"invalid-request",
     *   "message":"Invalid UUID: uuids must only contain letters, numbers, dashes, and curly brackets"
     * }
     */
    @Suppress("ReturnCount")
    private fun mapError(error: VolleyError): UploadEncryptedLogError {
        if (error is NoConnectionError) {
            return UploadEncryptedLogError.NoConnection
        }
        error.networkResponse?.let { networkResponse ->
            val statusCode = networkResponse.statusCode
            val dataString = String(networkResponse.data)
            val json = try {
                JSONObject(dataString)
            } catch (jsonException: JSONException) {
                AppLog.e(API, "Received response not in JSON format: " + jsonException.message)
                return UploadEncryptedLogError.Unknown(message = dataString)
            }
            val errorMessage = json.getString("message")
            json.getString("error").let { errorType ->
                if (errorType == INVALID_REQUEST) {
                    return UploadEncryptedLogError.InvalidRequest
                } else if (errorType == TOO_MANY_REQUESTS) {
                    return UploadEncryptedLogError.TooManyRequests
                }
            }
            return UploadEncryptedLogError.Unknown(statusCode, errorMessage)
        }
        return UploadEncryptedLogError.Unknown()
    }
}

internal sealed class UploadEncryptedLogResult {
    object LogUploaded : UploadEncryptedLogResult()
    class LogUploadFailed(val error: UploadEncryptedLogError) : UploadEncryptedLogResult()
}
