package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object Kit {
    val Page = ReaderBackground
    val Sheet = ReaderSheet
    val Surface = ReaderControl
    val SurfaceRaised = ReaderSelected
    val Outline = ReaderOutline
    val Divider = ReaderDivider
    val Text = ReaderForeground
    val TextMuted = ReaderHint
    val AccentUnread = ReaderChoiceBlue
    val Danger = ReaderDanger

    val OutlineWidth = ReaderOutlineWidth
    val CardShape = RoundedCornerShape(20.dp)
    val OptionShape = RoundedCornerShape(16.dp)
    val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val PillShape = RoundedCornerShape(percent = 50)

    val ScreenEdge = 12.dp
    val ControlGap = 8.dp
    val BubbleGap = 6.dp
    val SideChange = 16.dp
    val RowHeight = 52.dp
    val RoundButton = 40.dp
    val ControlHeight = 48.dp

    val Heading = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 30.sp)
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
    val BodyStrong = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 26.sp)
    val Hint = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp)
}

internal fun Modifier.kitSurface(shape: androidx.compose.ui.graphics.Shape = Kit.PillShape, fill: Color = Kit.Surface): Modifier =
    background(fill, shape).border(Kit.OutlineWidth, Kit.Outline, shape).clip(shape)

@Composable
internal fun KitRoundButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = Kit.Text,
) {
    Box(
        modifier.size(Kit.RoundButton).kitSurface(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(20.dp), tint = tint)
    }
}

@Composable
internal fun KitTitlePill(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.heightIn(min = Kit.RoundButton).kitSurface().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Kit.BodyStrong, color = Kit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun KitPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val fill = if (destructive) Kit.Danger else Kit.Text
    Box(
        modifier.heightIn(min = Kit.ControlHeight).alpha(if (enabled) 1f else 0.45f)
            .clip(Kit.PillShape).background(fill, Kit.PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Kit.BodyStrong, color = Kit.Page, maxLines = 1)
    }
}

@Composable
internal fun KitSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Box(
        modifier.heightIn(min = Kit.ControlHeight).alpha(if (enabled) 1f else 0.45f)
            .kitSurface()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Kit.BodyStrong, color = if (destructive) Kit.Danger else Kit.Text, maxLines = 1)
    }
}

@Composable
internal fun KitToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val track = if (checked) Kit.SurfaceRaised else Kit.Surface
    val thumb = if (checked) Kit.Text else Kit.TextMuted
    Box(
        modifier.width(52.dp).height(32.dp).kitSurface(fill = track)
            .let { if (onCheckedChange != null) it.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange) else it }
            .padding(4.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(thumb, CircleShape))
    }
}

@Composable
internal fun <T> KitSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().height(Kit.ControlHeight).kitSurface().padding(4.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                Modifier.weight(1f).height(40.dp).clip(Kit.PillShape)
                    .background(if (isSelected) Kit.SurfaceRaised else Color.Transparent, Kit.PillShape)
                    .selectable(isSelected, role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth(),
                    style = Kit.Body.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = if (isSelected) Kit.Text else Kit.TextMuted,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
internal fun KitListRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    destructive: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val color = if (destructive) Kit.Danger else Kit.Text
    Row(
        modifier.fillMaxWidth().heightIn(min = Kit.RowHeight)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = Kit.SideChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = color)
        Text(label, style = Kit.Body, color = color, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        trailing?.invoke(this)
    }
}

@Composable
internal fun KitListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().kitSurface(Kit.CardShape), content = content)
}

@Composable
internal fun KitDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Kit.Divider))
}

@Composable
internal fun KitSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Kit.Label,
        color = Kit.TextMuted,
        modifier = modifier.fillMaxWidth().padding(horizontal = Kit.SideChange, vertical = 8.dp),
    )
}

@Composable
internal fun KitSheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Kit.Sheet, Kit.SheetShape).padding(horizontal = Kit.SideChange, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp).background(Kit.TextMuted, CircleShape))
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Kit.Sheet,
        dragHandle = null,
        shape = Kit.SheetShape,
    ) {
        KitSheetSurface(content = content)
    }
}

@Composable
internal fun KitUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).background(Kit.AccentUnread, Kit.PillShape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = Kit.Label.copy(letterSpacing = 0.sp), color = Kit.Page)
    }
}

@Composable
internal fun KitAssistantBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(Kit.CardShape)
            .background(Brush.verticalGradient(listOf(ReaderBubbleAssistantTop, ReaderCard)), Kit.CardShape)
            .border(Kit.OutlineWidth, Kit.Outline, Kit.CardShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = Kit.Body, color = Kit.Text)
    }
}

@Composable
internal fun KitUserBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(Kit.CardShape)
            .background(Brush.verticalGradient(listOf(ReaderBubbleUserTop, ReaderUserCard)), Kit.CardShape)
            .border(Kit.OutlineWidth, ReaderBubbleUserOutline, Kit.CardShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, style = Kit.Body, color = Kit.Text, textAlign = TextAlign.Start)
    }
}

internal enum class KitOptionState { Idle, Picked, Dimmed }

@Composable
internal fun KitAnswerOption(
    letter: String,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    state: KitOptionState = KitOptionState.Idle,
    onClick: (() -> Unit)? = null,
) {
    val picked = state == KitOptionState.Picked
    Row(
        modifier.fillMaxWidth().alpha(if (state == KitOptionState.Dimmed) 0.45f else 1f)
            .kitSurface(Kit.OptionShape, if (picked) Kit.SurfaceRaised else Kit.Surface)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).background(if (picked) Kit.Text else Kit.SurfaceRaised, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = Kit.Page)
            else Text(letter, style = Kit.Hint.copy(fontWeight = FontWeight.Medium), color = Kit.Text)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Kit.BodyStrong.copy(lineHeight = 22.sp), color = Kit.Text)
            Text(detail, style = Kit.Hint, color = Kit.TextMuted)
        }
    }
}
