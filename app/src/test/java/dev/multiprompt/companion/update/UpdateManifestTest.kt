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

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInsecureApkUrl() {
        UpdateRelease.parse(
            """{"schemaVersion":1,"versionCode":2,"versionName":"2","apkUrl":"http://example.com/a.apk","sha256":"${"ab".repeat(32)}","sizeBytes":42}""",
        )
    }
}
