package io.github.ponpokoo.mastodonclient.feature.timeline

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import io.github.ponpokoo.mastodonclient.core.preferences.BottomTabBarStyle
import io.github.ponpokoo.mastodonclient.ui.theme.glassSurface

internal enum class MainDestination(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Home("ホーム", Icons.Outlined.Home, Icons.Filled.Home),
    Explore("探索", Icons.Outlined.Search, Icons.Filled.Search),
    Notifications("通知", Icons.Outlined.NotificationsNone, Icons.Filled.Notifications),
    Profile("プロフィール", Icons.Outlined.PersonOutline, Icons.Filled.Person),
}

private val TabPillSlideEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

@Composable
internal fun rememberBottomTabBarHazeState(style: BottomTabBarStyle, hidden: Boolean = false): HazeState? =
    if (style == BottomTabBarStyle.LiquidGlass && !hidden && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberHazeState()
    } else {
        null
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MainTabBar(
    style: BottomTabBarStyle,
    selected: MainDestination,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    hidden: Boolean = false,
    unreadNotifications: Int = 0,
    hazeState: HazeState? = null,
    onTabSelected: ((MainDestination) -> Unit)? = null,
) {
    // Keep the layout when the IME hides the bar from touch and accessibility.
    val visibilityModifier = Modifier.fillMaxWidth().graphicsLayer {
        alpha = if (hidden) 0f else 1f
    }.then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)
    if (style == BottomTabBarStyle.FullWidth) {
        FullWidthMainTabBar(
            selected = selected,
            windowInsets = windowInsets,
            hidden = hidden,
            unreadNotifications = unreadNotifications,
            visibilityModifier = visibilityModifier,
            onTabSelected = onTabSelected,
        )
    } else {
        FloatingMainTabBar(
            style = style,
            selected = selected,
            windowInsets = windowInsets,
            hidden = hidden,
            unreadNotifications = unreadNotifications,
            hazeState = hazeState,
            visibilityModifier = visibilityModifier,
            onTabSelected = onTabSelected,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FullWidthMainTabBar(
    selected: MainDestination,
    windowInsets: WindowInsets,
    hidden: Boolean,
    unreadNotifications: Int,
    visibilityModifier: Modifier,
    onTabSelected: ((MainDestination) -> Unit)?,
) {
    Box(visibilityModifier) {
        ShortNavigationBar(
            // Equal-weight items need a loose minimum width. The bar itself fills the maximum.
            modifier = Modifier.testTag("main_navigation_bar"),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f),
            windowInsets = windowInsets,
        ) {
            MainDestination.entries.forEach { item ->
                val isSelected = selected == item
                val colors = ShortNavigationBarItemDefaults.colors()
                ShortNavigationBarItem(
                    // The item already has 6 dp above its indicator; add 6 dp for a 12 dp top gap.
                    modifier = Modifier.padding(top = 6.dp).testTag("main_tab_${item.name.lowercase()}"),
                    selected = isSelected,
                    enabled = !hidden && onTabSelected != null,
                    onClick = { onTabSelected?.invoke(item) },
                    colors = if (onTabSelected != null) colors else colors.copy(
                        disabledIconColor = if (isSelected) colors.selectedIconColor else colors.unselectedIconColor,
                        disabledTextColor = if (isSelected) colors.selectedTextColor else colors.unselectedTextColor,
                    ),
                    icon = {
                        MainTabIcon(
                            item = item,
                            selected = isSelected,
                            unreadNotifications = unreadNotifications,
                        )
                    },
                    label = {
                        Text(item.label, modifier = Modifier.padding(horizontal = 4.dp), textAlign = TextAlign.Center)
                    },
                )
            }
        }
    }
}

@Composable
private fun FloatingMainTabBar(
    style: BottomTabBarStyle,
    selected: MainDestination,
    windowInsets: WindowInsets,
    hidden: Boolean,
    unreadNotifications: Int,
    hazeState: HazeState?,
    visibilityModifier: Modifier,
    onTabSelected: ((MainDestination) -> Unit)?,
) {
    val wideWindow = LocalWindowInfo.current.containerDpSize.width >= 600.dp
    val sideMargin = 16.dp
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val pillColor = if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.075f)
    }
    val selectedIndex = MainDestination.entries.indexOf(selected).coerceAtLeast(0)
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(durationMillis = 200, easing = TabPillSlideEasing),
        label = "main_tab_pill",
    )

    BoxWithConstraints(
        visibilityModifier.windowInsetsPadding(windowInsets)
            .padding(start = sideMargin, end = sideMargin, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Measure the proportion against the safe width before subtracting the minimum margins.
        val maxBarWidth = if (wideWindow) (maxWidth + sideMargin * 2) * 0.9f else 352.dp
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerLow
        val glass = hazeState.takeIf { style == BottomTabBarStyle.LiquidGlass && !hidden }

        Surface(
            modifier = Modifier.widthIn(max = maxBarWidth).fillMaxWidth().testTag("main_navigation_bar"),
            shape = CircleShape,
            color = if (glass == null) surfaceColor.copy(alpha = 0.94f) else Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 4.dp,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier
                    .glassSurface(glass, CircleShape, surfaceColor)
                    .padding(horizontal = 6.dp, vertical = 5.dp)
                    .fillMaxWidth()
                    .selectableGroup()
                    .drawBehind {
                        val count = MainDestination.entries.size
                        if (count == 0 || size.width <= 0f || size.height <= 0f) return@drawBehind
                        val slotWidth = size.width / count
                        val left = if (layoutDirection == LayoutDirection.Rtl) {
                            size.width - slotWidth * (animatedIndex + 1f)
                        } else {
                            slotWidth * animatedIndex
                        }
                        val radius = size.height / 2f
                        drawRoundRect(
                            color = pillColor,
                            topLeft = Offset(left, 0f),
                            size = Size(slotWidth, size.height),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MainDestination.entries.forEach { item ->
                    val isSelected = selected == item
                    val contentColor = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val itemModifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 52.dp)
                        .clip(CircleShape)
                        .then(
                            if (onTabSelected != null) {
                                Modifier.selectable(
                                    selected = isSelected,
                                    enabled = !hidden,
                                    role = Role.Tab,
                                    onClick = { onTabSelected(item) },
                                )
                            } else {
                                Modifier
                            },
                        )
                        .testTag("main_tab_${item.name.lowercase()}")
                    Column(
                        modifier = itemModifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        MainTabIcon(
                            item = item,
                            selected = isSelected,
                            unreadNotifications = unreadNotifications,
                            tint = contentColor,
                            iconModifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = item.label,
                            modifier = Modifier.padding(top = 2.dp),
                            color = contentColor,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MainTabIcon(
    item: MainDestination,
    selected: Boolean,
    unreadNotifications: Int,
    tint: Color = Color.Unspecified,
    iconModifier: Modifier = Modifier,
) {
    val iconVector = if (selected) item.selectedIcon else item.icon
    val iconContent: @Composable () -> Unit = {
        if (tint == Color.Unspecified) {
            Icon(iconVector, contentDescription = null, modifier = iconModifier)
        } else {
            Icon(iconVector, contentDescription = null, modifier = iconModifier, tint = tint)
        }
    }
    if (item == MainDestination.Notifications && unreadNotifications > 0) {
        val badgeText = if (unreadNotifications > 99) "99+" else unreadNotifications.toString()
        val badgeDescription = if (unreadNotifications > 99) "未読通知99件以上" else "未読通知${unreadNotifications}件"
        BadgedBox(
            badge = {
                Badge(modifier = Modifier.clearAndSetSemantics { contentDescription = badgeDescription }) {
                    Text(badgeText)
                }
            },
        ) {
            iconContent()
        }
    } else {
        iconContent()
    }
}
