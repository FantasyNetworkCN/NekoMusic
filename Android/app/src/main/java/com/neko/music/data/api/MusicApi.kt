package com.neko.music.data.api

import android.util.Log
import com.neko.music.data.model.ErrorResponse
import com.neko.music.data.model.Music
import com.neko.music.data.model.SearchRequest
import com.neko.music.data.model.SearchResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement

class MusicApi {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            logger = object : Logger {
                override fun log(message: String) {
                    Log.d("MusicApi", message)
                }
            }
            level = LogLevel.ALL
        }
    }
    
    private val baseUrl = "https://music.cnmsb.xin"
    
    suspend fun searchMusic(query: String): Result<List<Music>> {
        return try {
            Log.d("MusicApi", "Searching for: $query")
            val searchRequest = SearchRequest(query)
            val requestBody = json.encodeToString(searchRequest)
            Log.d("MusicApi", "Request body JSON: $requestBody")
            
            val response = client.post("$baseUrl/api/music/search") {
                contentType(Json)
                setBody(requestBody)
            }
            
            Log.d("MusicApi", "Response status: ${response.status}")
            val responseText = response.body<String>()
            Log.d("MusicApi", "Response raw text: $responseText")
            
            // 手动解析响应
            val jsonResponse = json.parseToJsonElement(responseText) as JsonObject
            val success = jsonResponse["success"]?.toString()?.toBoolean() ?: false
            val message = jsonResponse["message"]?.toString()?.removeSurrounding("\"") ?: ""
            val resultsArray = jsonResponse["results"]
            
            Log.d("MusicApi", "Parsed response - success: $success, message: $message, results: $resultsArray")
            
            if (success && resultsArray != null) {
                val results = json.decodeFromJsonElement<List<Music>>(resultsArray)
                Log.d("MusicApi", "Found ${results.size} results")
                Result.success(results)
            } else {
                Log.e("MusicApi", "Search failed: $message")
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Log.e("MusicApi", "Search error", e)
            Result.failure(e)
        }
    }
    
    suspend fun getMusicCoverUrl(music: Music): String {
        return "$baseUrl/api/music/cover/${music.id}"
    }
    
    suspend fun getMusicFileUrl(music: Music): String {
        return "$baseUrl/api/music/file/${music.id}"
    }
    
    suspend fun getMusicLyrics(music: Music): Result<String> {
        return try {
            Log.d("MusicApi", "Fetching lyrics for music: ${music.id}")
            val response = client.get("$baseUrl/api/music/lyrics/${music.id}")
            Log.d("MusicApi", "Response status: ${response.status}")
            val responseText = response.body<String>()
            Log.d("MusicApi", "Response raw text: $responseText")
            
            val jsonResponse = json.parseToJsonElement(responseText) as JsonObject
            val success = jsonResponse["success"]?.toString()?.toBoolean() ?: false
            val message = jsonResponse["message"]?.toString()?.removeSurrounding("\"") ?: ""
            val data = jsonResponse["data"]?.toString()?.removeSurrounding("\"")?.replace("\\n", "\n") ?: ""
            
            Log.d("MusicApi", "Parsed lyrics: $data")
            
            if (success) {
                Result.success(data)
            } else {
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Log.e("MusicApi", "Fetch lyrics error", e)
            Result.failure(e)
        }
    }
}