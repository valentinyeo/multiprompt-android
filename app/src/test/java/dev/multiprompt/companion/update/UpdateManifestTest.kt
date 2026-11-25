package dev.multiprompt.companion.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun parsesStrictUpdateManifest() {
        val release = UpdateRelease.parse(
            """
            {
              "schemaVersion": 1,
              "versionCode": 1002,
              "versionName": "0.1.2",
              "apkUrl": "https://example.com/app.apk",
              "sha256": "${"ab".repeat(32)}",
              "sizeBytes": 42,
              "notes": "Comfortable updates"
            }
            """.trimIndent(),
        )

        assertEquals(1002, release.versionCode)
        assertEquals("0.1.2", release.versionName)
    }

    @Test
    fun parsesManifestWithTrimmedHistory() {
        val release = UpdateRelease.parse(
            """
            {
              "schemaVersion": 1,
              "versionCode": 1003,
              "versionName": "0.1.3",
              "apkUrl": "https://example.com/app.apk",
              "sha256": "${"ab".repeat(32)}",
              "sizeBytes": 42,
              "notes": "Phones can update again",
              "commit": "abc",
              "history": [
                {"versionName": "0.1.3", "versionCode": 1003, "date": "2026-02-20", "notes": "- MUBO-553 Phone app cannot update"},
                {"versionName": "0.1.2", "versionCode": 1002, "date": "2026-02-19", "notes": "Comfortable updates"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("0.1.3", "0.1.2"), release.history.map { it.versionName })
        assertEquals(listOf("2026-02-20", "2026-02-19"), release.history.map { it.date })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureApkUrl() {
        UpdateRelease.parse(
            """{"schemaVersion":1,"versionCode":2,"versionName":"2","apkUrl":"http://example.com/a.apk","sha256":"${"ab".repeat(32)}","sizeBytes":42}""",
        )
    }
}
