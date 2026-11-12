package com.edu.standalone.sync.retrofit

import com.edu.standalone.sync.SyncOperation
import com.edu.standalone.sync.jvm.Wire
import com.edu.standalone.sync.remote.ApplyResult
import com.edu.standalone.sync.remote.ChangesPage
import com.edu.standalone.sync.remote.RemoteStore
import com.edu.standalone.sync.remote.TransientRemoteException
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

internal interface SyncApi {
    @POST("operations") fun apply(@Body body: RequestBody): Call<ResponseBody>
    @GET("changes") fun changes(@Query("after") after: Long, @Query("limit") limit: Int): Call<ResponseBody>
}

/** Synchronous Retrofit transport for protocol v1. Invoke on a worker thread. */
class RetrofitRemoteStore @JvmOverloads constructor(baseUrl: String,
    client: OkHttpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
) : RemoteStore {
    private val api: SyncApi = Retrofit.Builder()
        .baseUrl(baseUrl.trimEnd('/') + "/")
        .client(client)
        .build().create(SyncApi::class.java)

    override fun applyOperation(operation: SyncOperation): ApplyResult {
        val bytes = Wire.encode { Wire.operation(it, operation) }
        return request(api.apply(RequestBody.create(MediaType.parse("application/vnd.standalone.sync-v1"), bytes)), Wire::result)
    }

    override fun getChanges(afterCursor: Long, limit: Int): ChangesPage {
        require(afterCursor >= 0 && limit in 1..10000)
        return request(api.changes(afterCursor, limit), Wire::page)
    }

    private fun <T> request(call: Call<ResponseBody>, decode: (DataInputStream) -> T): T {
        try {
            val response = call.execute()
            val status = response.code()
            if (status >= 500 || status == 408 || status == 429) {
                response.errorBody()?.close()
                throw TransientRemoteException("HTTP $status")
            }
            if (status != 200) {
                response.errorBody()?.close()
                throw IllegalArgumentException("Protocol error: HTTP $status")
            }
            val body = requireNotNull(response.body()) { "Protocol error: empty response body" }
            return body.use { DataInputStream(it.byteStream()).use(decode) }
        } catch (e: IOException) {
            throw TransientRemoteException("HTTP exchange failed", e)
        }
    }
}
