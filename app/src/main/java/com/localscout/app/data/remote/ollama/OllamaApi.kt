package com.localscout.app.data.remote.ollama

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface OllamaApi {
    @POST("api/chat")
    suspend fun chat(@Body request: ChatRequest): ChatResponse

    @GET("api/tags")
    suspend fun tags(): TagsResponse
}
