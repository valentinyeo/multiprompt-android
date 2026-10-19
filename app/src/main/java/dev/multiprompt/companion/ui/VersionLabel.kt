package dev.multiprompt.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.multiprompt.companion.BuildConfig

@Composable
internal fun VersionLabel(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = BuildConfig.VERSION_NAME,
) {
    Text(
        text = versionName,
        modifier = modifier
            .padding(start = 4.dp)
            .widthIn(max = 72.dp)
            .testTag("app-version")
            .semantics { contentDescription = "Version $versionName, open release notes" }
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 4.dp),
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}
