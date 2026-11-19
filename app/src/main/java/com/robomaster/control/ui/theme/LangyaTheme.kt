package com.robomaster.control.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 狼牙视觉控制台配色（Material 3 浅色，Purple 主题）。
 *
 * 所有 UI 颜色只允许通过 **角色名** 引用（primary / surfaceContainer / outline …），
 * 禁止在界面代码里写死十六进制值 —— 角色集中在这里定义，换主题只改这一处。
 */
object LangyaColors {
    val Primary = Color(0xFF6750A4)
    val OnPrimary = Color(0xFFFFFFFF)
    val PrimaryContainer = Color(0xFFEADDFF)
    val OnPrimaryContainer = Color(0xFF21005D)

    val Secondary = Color(0xFF635A75)
    val OnSecondary = Color(0xFFFFFFFF)
    val SecondaryContainer = Color(0xFFE8DEF8)
    val OnSecondaryContainer = Color(0xFF1D192B)

    val Tertiary = Color(0xFF7D5260)
    val OnTertiary = Color(0xFFFFFFFF)
    val TertiaryContainer = Color(0xFFFFD8E4)
    val OnTertiaryContainer = Color(0xFF31111D)

    val Background = Color(0xFFFEF7FF)
    val OnBackground = Color(0xFF1D1B20)
    val Surface = Color(0xFFFEF7FF)
    val OnSurface = Color(0xFF1D1B20)
    val SurfaceVariant = Color(0xFFE7E0EC)
    val OnSurfaceVariant = Color(0xFF49454F)

    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF7F2FA)
    val SurfaceContainer = Color(0xFFF3EDF7)
    val SurfaceContainerHigh = Color(0xFFECE6F0)
    val SurfaceContainerHighest = Color(0xFFE6E0E9)

    val Outline = Color(0xFF79747E)
    val OutlineVariant = Color(0xFFCAC4D0)

    val InverseSurface = Color(0xFF322F35)
    val InverseOnSurface = Color(0xFFF5EFF7)
    val InversePrimary = Color(0xFFD0BCFF)

    val Error = Color(0xFFB3261E)
    val OnError = Color(0xFFFFFFFF)
    val ErrorContainer = Color(0xFFF9DEDC)
    val OnErrorContainer = Color(0xFF410E0B)
}

/** 只做浅色：比赛设备固定，不做深色与动态取色（避免不同机器配色漂移）。 */
val LangyaLightScheme: ColorScheme = lightColorScheme(
    primary = LangyaColors.Primary,
    onPrimary = LangyaColors.OnPrimary,
    primaryContainer = LangyaColors.PrimaryContainer,
    onPrimaryContainer = LangyaColors.OnPrimaryContainer,
    inversePrimary = LangyaColors.InversePrimary,
    secondary = LangyaColors.Secondary,
    onSecondary = LangyaColors.OnSecondary,
    secondaryContainer = LangyaColors.SecondaryContainer,
    onSecondaryContainer = LangyaColors.OnSecondaryContainer,
    tertiary = LangyaColors.Tertiary,
    onTertiary = LangyaColors.OnTertiary,
    tertiaryContainer = LangyaColors.TertiaryContainer,
    onTertiaryContainer = LangyaColors.OnTertiaryContainer,
    background = LangyaColors.Background,
    onBackground = LangyaColors.OnBackground,
    surface = LangyaColors.Surface,
    onSurface = LangyaColors.OnSurface,
    surfaceVariant = LangyaColors.SurfaceVariant,
    onSurfaceVariant = LangyaColors.OnSurfaceVariant,
    surfaceTint = LangyaColors.Primary,
    inverseSurface = LangyaColors.InverseSurface,
    inverseOnSurface = LangyaColors.InverseOnSurface,
    error = LangyaColors.Error,
    onError = LangyaColors.OnError,
    errorContainer = LangyaColors.ErrorContainer,
    onErrorContainer = LangyaColors.OnErrorContainer,
    outline = LangyaColors.Outline,
    outlineVariant = LangyaColors.OutlineVariant,
    surfaceBright = LangyaColors.Surface,
    surfaceDim = LangyaColors.SurfaceContainerHighest,
    surfaceContainer = LangyaColors.SurfaceContainer,
    surfaceContainerHigh = LangyaColors.SurfaceContainerHigh,
    surfaceContainerHighest = LangyaColors.SurfaceContainerHighest,
    surfaceContainerLow = LangyaColors.SurfaceContainerLow,
    surfaceContainerLowest = LangyaColors.SurfaceContainerLowest,
)

/**
 * 形状比例整体收紧（M3 Expressive 默认偏圆）：
 * 按钮/标签片按"高度的一半"由组件自己算，这里的档位只给卡片、图片、对话框、输入框用。
 */
val LangyaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 动效：等价于 `MotionScheme.standard()` 的曲线特征 —— 平滑、不回弹。
 *
 * 说明：material3 **1.4.0**（compileSdk 36 能用的最后一版）把 MaterialExpressiveTheme /
 * MotionScheme 标成 `internal`，公开 API 只到 MaterialTheme；1.5.0 又要求 compileSdk 37
 * （本机 SDK 与 AGP 8.7.3 最高 36/35）。因此这里按 standard 的规格落成常量：
 * 空间位移 300ms（emphasized decelerate）、快速反馈 150ms、效果过渡 200ms，
 * 全部 tween、**不用 spring**，避免回弹。
 */
object ConsoleMotion {
    private val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    private val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = tween(300, easing = EmphasizedDecelerate)
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = tween(150, easing = Standard)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = tween(200, easing = Standard)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = tween(100, easing = Standard)
}

/** 字体：Roboto（系统 sans 即 Roboto），字重按 M3 标准，不做额外压缩。 */
private val LangyaTypography = Typography()

@Composable
fun LangyaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LangyaLightScheme,
        shapes = LangyaShapes,
        typography = LangyaTypography,
        content = content,
    )
}

/** 便捷取色：界面里用 MaterialTheme.colorScheme.xxx（这里只是少写一层） */
val colors: ColorScheme
    @Composable get() = MaterialTheme.colorScheme
