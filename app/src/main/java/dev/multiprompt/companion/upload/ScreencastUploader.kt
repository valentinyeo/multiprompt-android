package dev.multiprompt.companion.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.multiprompt.companion.security.SecretStore
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ScreencastUploader(
    context: Context,
    private val secrets: SecretStore,
) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient()

    val configured: Boolean
        get() = secrets.get(SECRET_ID) != null

    fun saveSecret(value: String): Boolean {
        val secret = value.trim()
        if (secret.isBlank() || secret.length > MAX_SECRET_CHARACTERS) return false
        secrets.put(SECRET_ID, secret.toByteArray(Charsets.UTF_8))
        return true
    }

    suspend fun upload(uri: Uri): String = withContext(Dispatchers.IO) {
        val secret = secrets.get(SECRET_ID)?.toString(Charsets.UTF_8)?.ifBlank { null }
            ?: error("Add the Screencast2 upload key first")
        val png = imageAsPng(uri)
        val id = randomId()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "screenshot",
                "$id.png",
                png.toRequestBody("image/png".toMediaType()),
            )
            .addFormDataPart("id", id)
            .build()
        val request = Request.Builder()
            .url("https://screencast2.com/api/upload-screenshot")
            .header("Authorization", "Bearer $secret")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body.string().take(MAX_RESPONSE_CHARACTERS)
            require(response.isSuccessful) {
                if (response.code == 401) "Screencast2 rejected the upload key" else "Image upload failed (${response.code})"
            }
            val json = JSONObject(raw)
            require(json.optBoolean("success")) { json.optString("error", "Image upload failed") }
            json.getString("url").also { url ->
                require(url.startsWith("https://screencast2.com/")) {
                    "Screencast2 returned an invalid URL"
                }
            }
        }
    }

    private fun imageAsPng(uri: Uri): ByteArray {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: error("Could not read the selected image")
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not an image" }
        require(bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS) {
            "The selected image is too large"
        }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Could not decode the selected image")
        return bitmap.useBitmap {
            ByteArrayOutputStream().use { output ->
                require(it.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not encode the selected image"
                }
                require(output.size() <= MAX_PNG_BYTES) { "The selected image is too large" }
                output.toByteArray()
            }
        }
    }

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    private fun randomId(): String {
        val random = SecureRandom()
        return buildString(ID_LENGTH) {
            repeat(ID_LENGTH) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
        }
    }

    private companion object {
        const val SECRET_ID = "screencast2_upload_secret"
        const val MAX_SECRET_CHARACTERS = 512
        const val MAX_IMAGE_PIXELS = 16_000_000L
        const val MAX_PNG_BYTES = 25 * 1024 * 1024
        const val MAX_RESPONSE_CHARACTERS = 64 * 1024
        const val ID_LENGTH = 5
        const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    }
}
