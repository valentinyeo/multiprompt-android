package dev.multiprompt.companion.upload

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.io.InputStream

class DeviceFile(
    val name: String,
    val size: Long,
    val open: () -> InputStream,
) {
    companion object {
        fun from(resolver: ContentResolver, uri: Uri): DeviceFile {
            var name = ""
            var size = -1L
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn).orEmpty()
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
            val open: () -> InputStream = {
                resolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")
            }
            if (size < 0) size = open().use(::countBytes)
            return DeviceFile(name.ifBlank { uri.lastPathSegment.orEmpty() }, size, open)
        }

        internal fun countBytes(input: InputStream): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return total
                total += count
            }
        }
    }
}
