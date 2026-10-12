package com.tripnav.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Full-screen page: opaque background (so text always gets the theme's light content colour), a condensed
 * headline with an ember underline, and scrolling content.
 */
@Composable
fun Page(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scroll: Boolean = true,
    action: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = TN.Bg, contentColor = TN.TextHi) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 12.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title.uppercase(), style = MaterialTheme.typography.headlineMedium)
                    Box(Modifier.padding(top = 4.dp).width(36.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(TN.EmberGradient))
                }
                action()
            }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = TN.TextMid,
                    modifier = Modifier.padding(start = if (onBack != null) 52.dp else 20.dp, end = 20.dp, top = 8.dp))
            }
            val body = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            Column(if (scroll) body.weight(1f).verticalScroll(rememberScrollState()) else body.weight(1f)) {
                Spacer(Modifier.height(8.dp))
                content()
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Small tracked uppercase label that introduces a group of controls. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = TN.TextLo,
        modifier = modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
}

/** Rounded panel with a hairline border; the basic container for grouped content. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(TN.Surface1)
            .border(1.dp, accent?.copy(alpha = 0.55f) ?: TN.Outline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Panel whose rows are separated by hairlines (settings-style list). */
@Composable
fun GroupPanel(content: @Composable ColumnScope.() -> Unit) = Panel(padding = 0.dp, content = content)

@Composable
fun RowDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = TN.Outline)

/** A tappable list row: optional leading icon, title + optional detail, trailing content. */
@Composable
fun ListRow(
    title: String,
    detail: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = TN.TextMid,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(TN.Surface2), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = TN.TextHi, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TN.TextMid, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = trailing)
    }
}

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TN.TextLo)

@Composable
fun TNSwitch(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(
    checked = checked, onCheckedChange = onChange,
    colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = TN.Ember, checkedBorderColor = TN.Ember,
        uncheckedThumbColor = TN.TextMid, uncheckedTrackColor = TN.Surface2, uncheckedBorderColor = TN.OutlineStrong,
    ),
)

/** Status pill: coloured dot + label, used for connection and mode states. */
@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.clip(CircleShape).background(TN.Glass).border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = TN.TextHi)
    }
}

/** Big number with a small unit/label underneath, for stats. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier, color: Color = TN.TextHi) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color, maxLines = 1)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = TN.TextLo)
    }
}

/** Glass surface for overlays on the map. */
@Composable
fun Glass(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(24.dp), content: @Composable () -> Unit) {
    Surface(modifier, shape = shape, color = TN.Glass, contentColor = TN.TextHi, shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, TN.Outline), content = content)
}
