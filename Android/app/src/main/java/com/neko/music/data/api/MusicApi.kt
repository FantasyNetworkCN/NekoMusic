package com.neko.music.data.api

import com.neko.music.data.model.ErrorResponse
import com.neko.music.data.model.Music
import com.neko.music.data.model.SearchRequest
import com.neko.music.data.model.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class MusicApi {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
    
    private val baseUrl = "https://music.cnmsb.xin"
    
    suspend fun searchMusic(query: String): Result<List<Music>> {
        return try {
            val response = client.post("$baseUrl/api/music/search") {
                contentType(Json)
                setBody(SearchRequest(query))
            }
            
            val searchResponse = response.body<SearchResponse>()
            if (searchResponse.success) {
                Result.success(searchResponse.results ?: emptyList())
            } else {
                Result.failure(Exception(searchResponse.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getMusicCoverUrl(music: Music): String {
        return "$baseUrl${music.coverUrl}"
    }
    
    suspend fun getMusicFileUrl(music: Music): String {
        return "$baseUrl${music.filePath}"
    }
}