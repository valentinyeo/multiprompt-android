package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent

@Composable
internal fun ChatImagePreviews(runs: List<InlineRun>) {
    val urls = remember(runs) { runs.filterNot { it.code }.mapNotNull { it.url }
        .filter { it.startsWith("https://") || it.startsWith("http://") }.distinct() }
    urls.forEach { url ->
        var visible by remember(url) { mutableStateOf(false) }
        var expanded by remember(url) { mutableStateOf(false) }
        val view = LocalView.current
        Box(Modifier.fillMaxWidth().onGloballyPositioned {
            val bounds = it.boundsInWindow()
            if (bounds.top < view.height && bounds.bottom >= 0f && bounds.right > 0f && bounds.left < view.width) {
                visible = true
            }
        }) {
            if (visible) {
                SubcomposeAsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    loading = {},
                    error = {},
                    success = {
                        SubcomposeAsyncImageContent(
                            contentDescription = "Image preview: $url",
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = true },
                        )
                    },
                )
            }
        }
        if (expanded) {
            Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                var zoom by remember(url) { mutableFloatStateOf(1f) }
                var pan by remember(url) { mutableStateOf(Offset.Zero) }
                val transform = rememberTransformableState { scale, offset, _ ->
                    zoom = (zoom * scale).coerceAtLeast(1f)
                    pan = if (zoom == 1f) Offset.Zero else pan + offset
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AsyncImage(
                        model = url,
                        contentDescription = "Full screen image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = pan.x
                            translationY = pan.y
                        }.transformable(transform),
                    )
                    IconButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                        Icon(Icons.Default.Close, "Close image", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChatLinkText(text: String, content: @Composable (String) -> Unit) {
    val lines = remember(text) {
        if (MarkdownParser.parseInline(text).any { !it.code && it.url != null }) text.split('\n') else listOf(text)
    }
    Column {
        lines.forEach { line ->
            content(line)
            ChatImagePreviews(MarkdownParser.parseInline(line))
        }
    }
}

@Composable
internal fun ChatImageRuns(runs: List<InlineRun>, content: @Composable (List<InlineRun>) -> Unit) {
    val lines = remember(runs) {
        if (runs.none { !it.code && it.url != null }) listOf(runs) else buildList {
            var line = mutableListOf<InlineRun>()
            runs.forEach { run ->
                run.text.split('\n').forEachIndexed { index, part ->
                    if (index > 0) {
                        add(line)
                        line = mutableListOf()
                    }
                    line.add(run.copy(text = part))
                }
            }
            add(line)
        }
    }
    Column {
        lines.forEach { line ->
            content(line)
            ChatImagePreviews(line)
        }
    }
}
