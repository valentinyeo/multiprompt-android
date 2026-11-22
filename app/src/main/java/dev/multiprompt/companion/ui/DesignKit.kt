package dev.multiprompt.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue

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
    val ControlHeight = 48.dp
    val ControlInnerSize = 36.dp
    val ControlIconSize = 20.dp
    val InnerInset = (ControlHeight - ControlInnerSize) / 2
    val ControlTextInset = 5.dp

    val Heading = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 30.sp)
    val Body = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 26.sp)
    val BodyStrong = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 26.sp)
    val Hint = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
    val Label = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp)
}

@Composable
internal fun Modifier.kitSurface(
    shape: androidx.compose.ui.graphics.Shape = Kit.PillShape,
    fill: Color? = null,
): Modifier {
    val colors = kit()
    val paint = fill ?: colors.surface
    return background(paint, shape).border(Kit.OutlineWidth, colors.outline, shape).clip(shape)
}

@Composable
internal fun KitRoundButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = Kit.Text,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(Kit.ControlHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .kitSurface(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(Kit.ControlIconSize), tint = tint)
    }
}

@Composable
internal fun KitInnerControl(
    modifier: Modifier = Modifier,
    fill: Color = Kit.SurfaceRaised,
    outlined: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(Kit.ControlInnerSize)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (outlined) Modifier.kitSurface(CircleShape, fill) else Modifier.clip(CircleShape).background(fill, CircleShape))
            .let { if (onClick != null) it.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else it },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
internal fun KitInnerButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    fill: Color = Kit.SurfaceRaised,
    tint: Color = Kit.Text,
    outlined: Boolean = true,
    enabled: Boolean = true,
) {
    KitInnerControl(modifier = modifier, fill = fill, outlined = outlined, enabled = enabled, onClick = onClick) {
        Icon(icon, description, Modifier.size(Kit.ControlIconSize), tint = tint)
    }
}

@Composable
internal fun KitComposerPill(
    modifier: Modifier = Modifier,
    fill: Color = Kit.Surface,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Kit.ControlHeight).kitSurface(fill = fill).padding(Kit.InnerInset),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(Kit.InnerInset),
        content = content,
    )
}

@Composable
internal fun KitExpandButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = "Expand table",
) {
    Box(
        modifier.size(32.dp).kitSurface(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.OpenInFull, description, Modifier.size(16.dp), tint = Kit.Text)
    }
}

@Composable
internal fun KitTitlePill(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier.heightIn(min = Kit.ControlHeight).kitSurface().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Kit.BodyStrong, color = Kit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun KitSkillPill(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Kit.Body,
    current: Boolean = false,
    marked: Boolean = false,
) {
    val fill = when {
        current -> Kit.Text
        marked -> Kit.Outline
        else -> Kit.Surface
    }
    Text(
        text,
        modifier.kitSurface(fill = fill).padding(horizontal = 8.dp, vertical = 1.dp),
        style = style,
        color = if (current) Kit.Page else Kit.Text,
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
internal fun KitPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = kit()
    val fill = if (destructive) colors.danger else colors.text
    Box(
        modifier.heightIn(min = Kit.ControlHeight).alpha(if (enabled) 1f else 0.45f)
            .clip(Kit.PillShape).background(fill, Kit.PillShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Kit.BodyStrong, color = colors.page, maxLines = 1)
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
    val colors = kit()
    Box(
        modifier.heightIn(min = Kit.ControlHeight).alpha(if (enabled) 1f else 0.45f)
            .kitSurface()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Kit.BodyStrong, color = if (destructive) colors.danger else colors.text, maxLines = 1)
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
                Modifier.weight(1f).fillMaxHeight().clip(Kit.PillShape)
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
    enabled: Boolean = true,
) {
    val color = if (destructive) Kit.Danger else Kit.Text
    Row(
        modifier.fillMaxWidth().heightIn(min = Kit.RowHeight)
            .alpha(if (enabled) 1f else 0.45f)
            .let { if (onClick != null) it.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else it }
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
internal fun KitUsageBar(fraction: Float?, modifier: Modifier = Modifier) {
    val fill = fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    Box(modifier.fillMaxWidth().height(8.dp).clip(Kit.PillShape).background(Kit.Outline)) {
        if (fill != null && fill > 0f) {
            Box(Modifier.fillMaxWidth(fill).height(8.dp).background(Kit.Text))
        }
    }
}

@Composable
internal fun KitPlanBlock(
    name: String,
    percent: String,
    fraction: Float?,
    paceLine: String,
    detailLine: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().kitSurface(Kit.CardShape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = Kit.BodyStrong, color = Kit.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(percent, style = Kit.BodyStrong, color = Kit.Text, maxLines = 1)
        }
        KitUsageBar(fraction)
        Text(paceLine, style = Kit.Hint, color = Kit.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(detailLine, style = Kit.Hint, color = Kit.TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
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
internal fun KitSheetSurface(
    modifier: Modifier = Modifier,
    maxContentHeight: Dp = Dp.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = kit()
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .then(if (maxContentHeight != Dp.Unspecified) Modifier.heightIn(max = maxContentHeight) else Modifier)
            .background(colors.sheet, Kit.SheetShape)
            .padding(horizontal = Kit.SideChange, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(32.dp).height(4.dp).background(colors.textMuted, CircleShape))
        content()
    }
}

@Composable
internal fun ColumnScope.KitSheetScrollColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scrollState),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitSheet(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val colors = kit()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { dismissible || it != SheetValue.Hidden }),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = dismissible, shouldDismissOnClickOutside = dismissible),
        containerColor = colors.sheet,
        dragHandle = null,
        shape = Kit.SheetShape,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            KitSheetSurface(modifier = Modifier.fillMaxWidth(), maxContentHeight = maxHeight, content = content)
        }
    }
}

@Composable
internal fun KitMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    contentResetKey: String = "",
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) {
        KitSheet(onDismiss = onDismissRequest) {
            val sheet = this
            key(contentResetKey) {
                KitSheetScrollColumn {
                    KitListGroup(content = content)
                }
                if (footer != null) footer(sheet)
            }
        }
    }
}

@Composable
internal fun KitSavedNote(
    modifier: Modifier = Modifier,
    text: String = "Saved",
    fade: Boolean = true,
    onFinished: () -> Unit = {},
) {
    val opacity = remember { mutableFloatStateOf(1f) }
    if (fade) {
        LaunchedEffect(Unit) {
            delay(1_600)
            opacity.floatValue = 0.65f
            delay(150)
            opacity.floatValue = 0.3f
            delay(150)
            opacity.floatValue = 0f
            delay(100)
            onFinished()
        }
    }
    if (opacity.floatValue > 0f) {
        Box(
            modifier.alpha(opacity.floatValue).kitSurface().padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = Kit.Hint, color = Kit.Text, maxLines = 1)
        }
    }
}

@Composable
internal fun KitMenuRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    trailingText: String? = null,
    destructive: Boolean = false,
) {
    KitListRow(
        label = label,
        modifier = modifier,
        icon = icon,
        destructive = destructive,
        trailing = if (trailingText != null) {
            { Text(trailingText, style = Kit.Hint, color = if (destructive) Kit.Danger else Kit.TextMuted) }
        } else null,
        onClick = onClick,
        enabled = enabled,
    )
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
            if (detail.isNotBlank()) Text(detail, style = Kit.Hint, color = Kit.TextMuted)
        }
    }
}

@Composable
internal fun KitSearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSearch: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier.height(Kit.ControlHeight).kitSurface().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Kit.Body.copy(color = Kit.Text),
            cursorBrush = SolidColor(Kit.Text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = Kit.Body, color = Kit.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

@Composable
internal fun KitMatchCount(label: String, modifier: Modifier = Modifier) {
    Text(label, style = Kit.Hint, color = Kit.TextMuted, maxLines = 1, modifier = modifier)
}

@Composable
internal fun KitMatchMarker(text: String, current: Boolean, modifier: Modifier = Modifier) {
    val fill = if (current) Kit.Text else Kit.Outline
    val color = if (current) Kit.Page else Kit.Text
    Box(modifier.background(fill, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp)) {
        Text(text, style = Kit.Body, color = color, maxLines = 1)
    }
}

@Composable
internal fun KitNameField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = kit()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = Kit.Hint, color = if (error != null) colors.danger else colors.textMuted)
        Column(
            Modifier.fillMaxWidth().kitSurface(ControlShape, colors.surface).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Kit.Body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.text),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { message ->
                Text(message, style = Kit.Hint, color = colors.danger)
            }
        }
    }
}

@Composable
internal fun RenameSessionSheet(
    draft: String,
    onDraftChange: (String) -> Unit,
    error: String?,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
) {
    val colors = kit()
    KitSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Rename session", style = Kit.Heading, color = colors.text)
            KitNameField(
                value = draft,
                onValueChange = onDraftChange,
                label = "Name",
                error = error,
                modifier = Modifier.testTag("rename-name-field"),
                keyboardActions = KeyboardActions(onDone = { onRename() }),
            )
            KitSecondaryButton("Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            KitPrimaryButton("Rename", onClick = onRename, modifier = Modifier.fillMaxWidth())
        }
    }
}
