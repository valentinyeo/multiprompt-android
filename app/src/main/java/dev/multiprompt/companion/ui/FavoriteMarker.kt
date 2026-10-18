package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object FavoriteMarker {
    const val DOT_ARGB: Long = 0xFFF0C44B
    const val DOT_DP: Int = 6
}

@Composable
fun FavoriteDot(modifier: Modifier = Modifier) {
    Box(modifier.size(FavoriteMarker.DOT_DP.dp).background(Color(FavoriteMarker.DOT_ARGB), CircleShape))
}
