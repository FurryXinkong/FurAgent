package net.xinkong.furagent.ui.theme

import androidx.compose.ui.graphics.Color

// ===== Light =====
// 【改】主题色从纯蓝 #3B82F6 换成蓝紫 #6E7CFA，
// 与流体渐变（#4A9EFF -> #A78BFA）同一色系
val LightPrimary = Color(0xFF6E7CFA)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFEAECFE)
val LightOnPrimaryContainer = Color(0xFF3D3FA8)

val LightBackground = Color(0xFFF5F5F7)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceGlass = Color(0xE6FFFFFF)
val LightSurfaceVariant = Color(0xFFF0F0F2)
val LightOnSurface = Color(0xFF1C1C1E)
val LightOnSurfaceVariant = Color(0xFF6E6E73)
val LightOutline = Color(0xFFE5E5EA)
val LightOutlineVariant = Color(0xFFEFEFF4)
val LightError = Color(0xFFFF3B30)
val LightSuccess = Color(0xFF34C759)

// ===== Dark =====
// 【改】对应提亮版蓝紫
val DarkPrimary = Color(0xFF8B93FF)
val DarkOnPrimary = Color(0xFF0A0A0C)
val DarkPrimaryContainer = Color(0xFF2A2C52)
val DarkOnPrimaryContainer = Color(0xFFC9CBFF)

val DarkBackground = Color(0xFF0A0A0C)
val DarkSurface = Color(0xFF1C1C1E)
val DarkSurfaceGlass = Color(0xCC1C1C1E)
val DarkSurfaceVariant = Color(0xFF2C2C2E)
val DarkOnSurface = Color(0xFFF5F5F7)
val DarkOnSurfaceVariant = Color(0xFF8E8E93)
val DarkOutline = Color(0xFF38383A)
val DarkOutlineVariant = Color(0xFF2A2A2C)
val DarkError = Color(0xFFFF453A)
val DarkSuccess = Color(0xFF30D158)

// ===== SurfaceContainer 系列 =====
// Material3 的 AlertDialog / DropdownMenu / Sheet 会用到这一组。
// 不显式指定的话，lightColorScheme() / darkColorScheme() 会用它们自带的默认值，
// 导致深色模式下弹窗背景依旧是浅色。
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFFAFAFC)
val LightSurfaceContainer = Color(0xFFF3F3F5)
val LightSurfaceContainerHigh = Color(0xFFEDEDEF)
val LightSurfaceContainerHighest = Color(0xFFE7E7EA)

val DarkSurfaceContainerLowest = Color(0xFF0A0A0C)
val DarkSurfaceContainerLow = Color(0xFF151517)
val DarkSurfaceContainer = Color(0xFF1C1C1E)
val DarkSurfaceContainerHigh = Color(0xFF26262A)
val DarkSurfaceContainerHighest = Color(0xFF303034)