package com.robomaster.control.ui.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.robomaster.control.ui.theme.ConsoleMotion

/**
 * 控制台组件库（Material 3 Expressive）。
 *
 * 约定：这里只做「把标准组件调成规格里的尺寸/形状」，不自己画控件。
 * 颜色全部取 [MaterialTheme.colorScheme] 的角色。
 */

/** 按钮中号高度：M3 尺寸表 M = 56dp */
val ButtonHeight: Dp = 56.dp

/** 按钮/标签片的外侧圆角 = 高度的一半 */
private val ButtonShape = RoundedCornerShape(ButtonHeight / 2)

/** 相连按钮组的**内侧**圆角（3dp 间距，只缩内侧） */
private val ConnectedInner = 8.dp

/** 点击反馈：涟漪（Button 自带）+ 轻微缩放，松手回弹由 standard 动效曲线管 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = ConsoleMotion.fastSpatial(),
        label = "press-scale",
    )
    return this.scale(s)
}

/** 按钮风格 */
enum class ConsoleButtonKind { Filled, Tonal, Outlined }

/**
 * 控制台按钮（中号 56dp）。
 *
 * @param width 给固定宽度（草图里写死的 136dp / 140dp / 576dp）；null 则按内容自适应
 */
@Composable
fun ConsoleButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ConsoleButtonKind = ConsoleButtonKind.Filled,
    icon: ImageVector? = null,
    width: Dp? = null,
    height: Dp = ButtonHeight,
    shape: Shape = ButtonShape,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = when (kind) {
        ConsoleButtonKind.Filled -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        ConsoleButtonKind.Tonal -> ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        ConsoleButtonKind.Outlined -> ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        )
    }
    var mod: Modifier = modifier.height(height).pressScale(interaction)
    if (width != null) mod = mod.width(width)

    Button(
        onClick = onClick,
        modifier = mod,
        enabled = enabled,
        shape = shape,
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
        border = if (kind == ConsoleButtonKind.Outlined) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        } else null,
        // 窄的定宽按钮（136/140dp）用更小的左右内边距，否则「强制接管串口」会被省略号截断
        contentPadding = PaddingValues(horizontal = if (width != null && width < 180.dp) 12.dp else 20.dp),
        interactionSource = interaction,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 相连按钮组（M3 Expressive Connected button group）。
 *
 * 3dp 间距；只有**相邻的内侧**圆角缩到 8dp，第一个的左外与最后一个的右外保持
 * 半高圆角。选中项用 primary 填充，未选中用 secondaryContainer 色调。
 */
@Composable
fun ConnectedButtonGroup(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = ButtonHeight,
    icons: List<ImageVector?> = List(labels.size) { null },
    /** true = 全部用 primary 填充（动作组）；false = 选中项 primary、其余 secondaryContainer（选择组） */
    allFilled: Boolean = false,
) {
    val n = labels.size
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { i, label ->
            val outer = height / 2
            val shape = RoundedCornerShape(
                topStart = if (i == 0) outer else ConnectedInner,
                bottomStart = if (i == 0) outer else ConnectedInner,
                topEnd = if (i == n - 1) outer else ConnectedInner,
                bottomEnd = if (i == n - 1) outer else ConnectedInner,
            )
            val sel = allFilled || i == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = { onSelect(i) },
                // 最后一组「向右拉伸占满剩余宽度」：由调用方给 Row 的 weight，
                // 组内每个按钮均分，所以这里用 weight(1f)
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .pressScale(interaction),
                shape = shape,
                colors = if (sel) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                },
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                interactionSource = interaction,
            ) {
                val ic = icons.getOrNull(i)
                if (ic != null) {
                    Icon(ic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 描边文本输入框（高 56dp，圆角 16dp）。
 *
 * 聚焦时标签上浮、边框变 2dp primary —— 由 M3 标准组件负责，这里只调形状与尺寸。
 */
@Composable
fun ConsoleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        placeholder = placeholder?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
        },
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText?.let {
            { Text(it, style = MaterialTheme.typography.bodySmall) }
        },
        shape = RoundedCornerShape(16.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/**
 * 描边下拉菜单（exposed dropdown）。
 *
 * 外观与 [ConsoleField] 一致，末尾 arrow_drop_down；点开在下方展开
 * surfaceContainer、4dp 圆角、48dp 项高的菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            placeholder = {
                Text("未选择", style = MaterialTheme.typography.bodyMedium)
            },
            leadingIcon = leadingIcon?.let {
                { Icon(it, contentDescription = null, modifier = Modifier.size(20.dp)) }
            },
            trailingIcon = {
                Icon(
                    Icons.Rounded.ArrowDropDown,
                    contentDescription = "展开",
                    modifier = Modifier.size(24.dp),
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(4.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.exposedDropdownSize(),
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                    modifier = Modifier.height(48.dp),
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** 复选框（18dp，2dp 圆角，勾选 primary）+ 右侧 bodyLarge 标签，整行可点 */
@Composable
fun ConsoleCheckbox(
    checked: Boolean,
    label: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.toggleable(
            value = checked,
            onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.size(18.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = MaterialTheme.colorScheme.primary,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = LocalContentColor.current,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * M3 Expressive 粗轨道滑块：轨道 16dp，手柄 4×44dp；
 * 手柄左侧 primary、右侧 secondaryContainer。可拖动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueSuffix: String = "",
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.secondaryContainer,
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
        inactiveTickColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                enabled = true,
                thumbSize = DpSize(4.dp, 44.dp),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(16.dp),
                colors = colors,
                enabled = true,
            )
        },
    )
}

/** 容器框：只有背景色与圆角，没有任何行为（规格原文） */
@Composable
fun ConsolePanel(
    modifier: Modifier = Modifier,
    corner: Dp = 28.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxWidth()) { content() }
    }
}
