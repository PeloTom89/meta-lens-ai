package com.metalens.app.pictureanalysis

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal, single-shot image analysis client (HTTP) for Picture Analysis.
 *
 * Uses OpenAI Responses API with an input_image (data URL) + text prompt.
 */
class OpenAIImageAnalysisService(
    private val client: OkHttpClient = defaultClient(),
) {
    companion object {
        private const val ENDPOINT = "https://api.openai.com/v1/responses"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    fun analyzeImage(
        apiKey: String,
        model: String,
        prompt: String,
        bitmap: Bitmap,
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Missing OpenAI API key"))
        }

        return runCatching {
            val jpegBytes = bitmap.toJpegBytes(quality = 85)
            val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            val input =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "input_text")
                                            .put("text", prompt),
                                    )
                                    .put(
                                        JSONObject()
                                            .put("type", "input_image")
                                            .put("image_url", dataUrl),
                                    ),
                            ),
                    )

            val payload =
                JSONObject()
                    .put("model", model)
                    .put("input", input)
                    .put("max_output_tokens", 250)
                    .toString()

            val request =
                Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(payload.toRequestBody(JSON))
                    .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${body.take(500)}")
                }
                parseResponseText(body)
            }
        }
    }

    private fun parseResponseText(rawJson: String): String {
        val obj = JSONObject(rawJson)

        // Preferred if present.
        val outputText = obj.optString("output_text")
        if (outputText.isNotBlank()) return outputText.trim()

        // Fallback: concatenate any text segments in output[*].content[*].text
        val out = obj.optJSONArray("output") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val seg = content.optJSONObject(j) ?: continue
                val text = seg.optString("text")
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text.trim())
                }
            }
        }

        val result = sb.toString().trim()
        if (result.isBlank()) {
            throw IllegalStateException("Empty response")
        }
        return result
    }
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    if (!compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)) {
        throw IllegalStateException("Failed to encode JPEG")
    }
    return out.toByteArray()
}

