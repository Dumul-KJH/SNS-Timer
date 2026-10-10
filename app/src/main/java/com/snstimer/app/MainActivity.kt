package com.snstimer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snstimer.app.data.DailyUsageReport
import com.snstimer.app.data.DailyUsageStore
import com.snstimer.app.data.AttentionEffectType
import com.snstimer.app.data.InstalledApp
import com.snstimer.app.data.InstalledAppsRepository
import com.snstimer.app.data.OverlayAppearanceSettings
import com.snstimer.app.data.OverlayAppearanceStore
import com.snstimer.app.data.TargetAppsStore
import com.snstimer.app.data.ShortVideoCountStore
import com.snstimer.app.overlay.OverlayTimerService
import com.snstimer.app.permission.PermissionChecker
import com.snstimer.app.ui.theme.SnsTimerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SnsTimerTheme {
                SnsTimerScreen()
            }
        }
    }
}

@Composable
private fun SnsTimerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val targetStore = remember { TargetAppsStore(context) }
    val appsRepository = remember { InstalledAppsRepository(context) }
    val dailyUsageStore = remember { DailyUsageStore(context) }
    val shortVideoCountStore = remember { ShortVideoCountStore(context) }
    val appearanceStore = remember { OverlayAppearanceStore(context) }

    var overlayGranted by remember { mutableStateOf(PermissionChecker.canDrawOverlays(context)) }
    var usageGranted by remember { mutableStateOf(PermissionChecker.hasUsageStatsAccess(context)) }
    var monitoring by remember { mutableStateOf(OverlayTimerService.isRunning()) }
    var selectedPackages by remember { mutableStateOf(targetStore.getSelectedPackages()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var showAppearanceSettings by remember { mutableStateOf(false) }
    var statsRefresh by remember { mutableStateOf(0) }
    var shortVideoRefresh by remember { mutableStateOf(0) }
    var appearance by remember { mutableStateOf(appearanceStore.getSettings()) }
    var accessibilityEnabled by remember { mutableStateOf(isShortVideoAccessibilityEnabled(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Service can still start; notification may be limited */ }

    fun refreshState() {
        overlayGranted = PermissionChecker.canDrawOverlays(context)
        usageGranted = PermissionChecker.hasUsageStatsAccess(context)
        monitoring = OverlayTimerService.isRunning()
        selectedPackages = targetStore.getSelectedPackages()
        accessibilityEnabled = isShortVideoAccessibilityEnabled(context)
        statsRefresh++
        shortVideoRefresh++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        appsLoading = true
        val loadedApps = withContext(Dispatchers.Default) {
            appsRepository.loadLaunchableApps()
        }
        installedApps = loadedApps
        selectedPackages = targetStore.initializeDefaultSelection(
            appsRepository.defaultTargetPackages(loadedApps),
        )
        appsLoading = false
    }

    LaunchedEffect(selectedTab) {
        while (true) {
            delay(1_000L)
            if (selectedTab == ANALYTICS_TAB) statsRefresh++ else shortVideoRefresh++
        }
    }

    val allGranted = overlayGranted && usageGranted
    val filteredApps = remember(installedApps, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.contains(q, ignoreCase = true) ||
                    it.packageName.contains(q, ignoreCase = true)
            }
        }
    }
    val selectedApps = remember(installedApps, selectedPackages) {
        installedApps.filter { it.packageName in selectedPackages }
    }
    val usageReport = remember(selectedPackages, statsRefresh) {
        dailyUsageStore.getReport(selectedPackages)
    }
    val shortVideoCounts = remember(selectedPackages, shortVideoRefresh) {
        shortVideoCountStore.getTodayCounts(selectedPackages)
    }

    fun togglePackage(packageName: String, selected: Boolean) {
        targetStore.setSelected(packageName, selected)
        selectedPackages = targetStore.getSelectedPackages()
    }

    fun selectAllApps(selected: Boolean) {
        val packages = if (selected) installedApps.map { it.packageName }.toSet() else emptySet()
        targetStore.setSelectedPackages(packages)
        selectedPackages = targetStore.getSelectedPackages()
    }

    fun updateAppearance(settings: OverlayAppearanceSettings) {
        appearance = settings
        appearanceStore.saveSettings(settings)
    }

    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startMonitoring() {
        if (!allGranted || selectedPackages.isEmpty()) return
        ensureNotificationPermission()
        OverlayTimerService.start(context)
        monitoring = true
    }

    fun stopMonitoring() {
        OverlayTimerService.stop(context)
        monitoring = false
    }

    Scaffold(
        bottomBar = {
            if (selectedTab == TIMER_TAB && allGranted) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        if (selectedPackages.isEmpty()) {
                            Text(
                                text = stringResource(R.string.target_apps_none_selected),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        if (monitoring) {
                            OutlinedButton(
                                onClick = ::stopMonitoring,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(text = stringResource(R.string.stop_monitoring))
                            }
                        } else {
                            Button(
                                onClick = ::startMonitoring,
                                enabled = selectedPackages.isNotEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text(text = stringResource(R.string.start_monitoring))
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Timer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    !allGranted -> stringResource(R.string.grant_permissions_hint)
                    monitoring -> stringResource(R.string.monitoring_active)
                    else -> stringResource(R.string.all_permissions_ready)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == TIMER_TAB,
                    onClick = { selectedTab = TIMER_TAB },
                    text = { Text(stringResource(R.string.timer_tab)) },
                )
                Tab(
                    selected = selectedTab == ANALYTICS_TAB,
                    onClick = {
                        selectedTab = ANALYTICS_TAB
                        showAppearanceSettings = false
                    },
                    text = { Text(stringResource(R.string.analytics_tab)) },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == TIMER_TAB) {
                if (showAppearanceSettings) {
                    OutlinedButton(
                        onClick = { showAppearanceSettings = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(text = stringResource(R.string.back_to_timer_settings))
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OverlayAppearanceSection(
                        settings = appearance,
                        onSettingsChange = ::updateAppearance,
                    )
                } else {
                    PermissionCard(
                        icon = Icons.Outlined.Layers,
                        title = stringResource(R.string.permission_overlay_title),
                        body = stringResource(R.string.permission_overlay_body),
                        granted = overlayGranted,
                        onOpenSettings = {
                            context.startActivity(PermissionChecker.overlaySettingsIntent(context))
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PermissionCard(
                        icon = Icons.Outlined.QueryStats,
                        title = stringResource(R.string.permission_usage_title),
                        body = stringResource(R.string.permission_usage_body),
                        granted = usageGranted,
                        onOpenSettings = {
                            context.startActivity(PermissionChecker.usageAccessSettingsIntent())
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ShortVideoDetectionCard(
                        enabled = accessibilityEnabled,
                        todayCount = shortVideoCounts.values.sum(),
                        onOpenSettings = {
                            context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { showAppearanceSettings = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(text = stringResource(R.string.open_overlay_settings))
                    }

                    if (allGranted) {
                        Spacer(modifier = Modifier.height(20.dp))
                        TargetAppsSection(
                            apps = filteredApps,
                            allApps = installedApps,
                            selectedApps = selectedApps,
                            selectedPackages = selectedPackages,
                            appsLoading = appsLoading,
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            onToggle = ::togglePackage,
                            onSelectAll = ::selectAllApps,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            } else if (selectedTab == ANALYTICS_TAB) {
                UsageAnalyticsSection(
                    report = usageReport,
                    apps = selectedApps,
                    selectedCount = selectedPackages.size,
                    shortVideoCounts = shortVideoCounts,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun OverlayAppearanceSection(
    settings: OverlayAppearanceSettings,
    onSettingsChange: (OverlayAppearanceSettings) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OverlayTimerPreview(settings)
        Text(
            text = stringResource(R.string.overlay_settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.overlay_settings_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SettingSlider(
                    title = stringResource(R.string.overlay_time_size),
                    valueLabel = stringResource(
                        R.string.overlay_time_size_value,
                        settings.timeTextSizeSp.roundToInt(),
                    ),
                    value = settings.timeTextSizeSp,
                    valueRange = 14f..32f,
                    steps = 17,
                    onValueChange = { onSettingsChange(settings.copy(timeTextSizeSp = it)) },
                )
                OverlayColorOptions(
                    title = stringResource(R.string.overlay_text_color),
                    selectedColor = settings.textColor,
                    options = TEXT_COLOR_OPTIONS,
                    onColorSelected = { onSettingsChange(settings.copy(textColor = it)) },
                )
                OverlayColorOptions(
                    title = stringResource(R.string.overlay_background_color),
                    selectedColor = settings.backgroundColor,
                    options = BACKGROUND_COLOR_OPTIONS,
                    onColorSelected = { onSettingsChange(settings.copy(backgroundColor = it)) },
                )
                SettingSlider(
                    title = stringResource(R.string.overlay_opacity),
                    valueLabel = stringResource(
                        R.string.overlay_opacity_value,
                        (settings.backgroundTransparency * 100).roundToInt(),
                    ),
                    value = settings.backgroundTransparency,
                    valueRange = 0f..1f,
                    steps = 99,
                    onValueChange = {
                        onSettingsChange(settings.copy(backgroundTransparency = it))
                    },
                )
                Text(
                    text = stringResource(R.string.overlay_effect_type),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AttentionEffectType.values().forEach { effectType ->
                        FilterChip(
                            selected = settings.attentionEffectType == effectType,
                            onClick = {
                                onSettingsChange(settings.copy(attentionEffectType = effectType))
                            },
                            label = {
                                Text(
                                    text = stringResource(
                                        when (effectType) {
                                            AttentionEffectType.SHAKE -> R.string.effect_shake
                                            AttentionEffectType.PULSE -> R.string.effect_pulse
                                            AttentionEffectType.BOUNCE -> R.string.effect_bounce
                                            AttentionEffectType.BLINK -> R.string.effect_blink
                                        },
                                    ),
                                )
                            },
                        )
                    }
                }
                SettingSlider(
                    title = stringResource(R.string.overlay_effect_interval),
                    valueLabel = stringResource(
                        R.string.overlay_effect_interval_value,
                        settings.attentionIntervalMinutes,
                    ),
                    value = settings.attentionIntervalMinutes.toFloat(),
                    valueRange = 1f..60f,
                    steps = 58,
                    onValueChange = {
                        onSettingsChange(settings.copy(attentionIntervalMinutes = it.roundToInt()))
                    },
                )
                SettingSlider(
                    title = stringResource(R.string.overlay_effect_size),
                    valueLabel = stringResource(
                        R.string.overlay_effect_size_value,
                        settings.attentionEffectSizeDp.roundToInt(),
                    ),
                    value = settings.attentionEffectSizeDp,
                    valueRange = 1f..12f,
                    steps = 10,
                    onValueChange = {
                        onSettingsChange(settings.copy(attentionEffectSizeDp = it))
                    },
                )
                OutlinedButton(
                    onClick = { onSettingsChange(OverlayAppearanceSettings()) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.reset_overlay_appearance))
                }
            }
        }
    }
}

@Composable
private fun OverlayTimerPreview(settings: OverlayAppearanceSettings) {
    var effectPreviewCount by remember { mutableStateOf(0) }
    val shakeProgress = remember { Animatable(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    LaunchedEffect(effectPreviewCount) {
        if (effectPreviewCount > 0) {
            shakeProgress.snapTo(0f)
            shakeProgress.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 420
                    1f at 70
                    -1f at 140
                    0.65f at 210
                    -0.65f at 280
                    0f at 420
                },
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.overlay_preview_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Start),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color(settings.backgroundColor).copy(
                        alpha = 1f - settings.backgroundTransparency.coerceIn(0f, 1f),
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.graphicsLayer {
                        val effectPx = with(density) { settings.attentionEffectSizeDp.dp.toPx() }
                        val progress = shakeProgress.value
                        val strength = kotlin.math.abs(progress)
                        when (settings.attentionEffectType) {
                            AttentionEffectType.SHAKE -> {
                                translationX = progress * effectPx
                                rotationZ = progress * settings.attentionEffectSizeDp * 0.3f
                            }
                            AttentionEffectType.PULSE -> {
                                val scale = 1f + strength *
                                    (settings.attentionEffectSizeDp * 0.04f).coerceIn(0.04f, 0.4f)
                                scaleX = scale
                                scaleY = scale
                            }
                            AttentionEffectType.BOUNCE -> {
                                translationY = -strength * effectPx
                            }
                            AttentionEffectType.BLINK -> {
                                alpha = 1f - strength *
                                    (settings.attentionEffectSizeDp * 0.12f).coerceIn(0.1f, 0.8f)
                            }
                        }
                    },
                ) {
                    Text(
                        text = "12:34",
                        color = Color(settings.textColor),
                        fontSize = settings.timeTextSizeSp.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            OutlinedButton(
                onClick = { effectPreviewCount++ },
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(text = stringResource(R.string.overlay_preview_effect))
            }
        }
    }
}

@Composable
private fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverlayColorOptions(
    title: String,
    selectedColor: Int,
    options: List<OverlayColorOption>,
    onColorSelected: (Int) -> Unit,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            options.forEach { option ->
                Column(
                    modifier = Modifier.clickable { onColorSelected(option.color) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(option.color))
                            .border(
                                width = if (selectedColor == option.color) 3.dp else 1.dp,
                                color = if (selectedColor == option.color) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            ),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = option.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private data class OverlayColorOption(val label: String, val color: Int)

private val TEXT_COLOR_OPTIONS = listOf(
    OverlayColorOption("흰색", 0xFFFFFFFF.toInt()),
    OverlayColorOption("검정", 0xFF111111.toInt()),
    OverlayColorOption("노랑", 0xFFFFEB3B.toInt()),
    OverlayColorOption("민트", 0xFF80CBC4.toInt()),
    OverlayColorOption("분홍", 0xFFFF80AB.toInt()),
    OverlayColorOption("하늘", 0xFF80D8FF.toInt()),
)

private val BACKGROUND_COLOR_OPTIONS = listOf(
    OverlayColorOption("초록", 0xFF1B5E4A.toInt()),
    OverlayColorOption("검정", 0xFF151515.toInt()),
    OverlayColorOption("남색", 0xFF17324D.toInt()),
    OverlayColorOption("보라", 0xFF4A235A.toInt()),
    OverlayColorOption("빨강", 0xFF8B1E3F.toInt()),
    OverlayColorOption("회색", 0xFF616161.toInt()),
)

@Composable
private fun UsageAnalyticsSection(
    report: DailyUsageReport,
    apps: List<InstalledApp>,
    selectedCount: Int,
    shortVideoCounts: Map<String, Int>,
) {
    val sortedApps = remember(apps, report.apps) {
        apps.sortedByDescending { report.apps[it.packageName]?.todayMs ?: 0L }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(
                R.string.analytics_date,
                SimpleDateFormat("yyyy년 M월 d일", Locale.KOREA).format(Date()),
            ),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        AnalyticsMetricCard(
            title = stringResource(R.string.analytics_today),
            value = formatUsageDuration(report.todayTotalMs),
        )
        AnalyticsMetricCard(
            title = stringResource(R.string.short_video_today_count),
            value = stringResource(R.string.short_video_count_value, shortVideoCounts.values.sum()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnalyticsMetricCard(
                title = stringResource(R.string.analytics_week_total),
                value = formatUsageDuration(report.sevenDayTotalMs),
                modifier = Modifier.weight(1f),
            )
            AnalyticsMetricCard(
                title = stringResource(R.string.analytics_week_average),
                value = formatUsageDuration(report.sevenDayAverageMs),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AnalyticsMetricCard(
                title = stringResource(R.string.analytics_month_total),
                value = formatUsageDuration(report.thirtyDayTotalMs),
                modifier = Modifier.weight(1f),
            )
            AnalyticsMetricCard(
                title = stringResource(R.string.analytics_month_average),
                value = formatUsageDuration(report.thirtyDayAverageMs),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.analytics_app_breakdown),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (selectedCount > 0 && apps.isNotEmpty()) {
            Text(
                text = stringResource(R.string.analytics_sort_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
            )
        }
        when {
            selectedCount == 0 -> Text(stringResource(R.string.analytics_no_apps))
            apps.isEmpty() -> Text(stringResource(R.string.analytics_apps_loading))
            else -> sortedApps.forEach { app ->
                AppAnalyticsCard(
                    app = app,
                    usage = report.apps[app.packageName],
                )
            }
        }
        Text(
            text = stringResource(R.string.analytics_period_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun ShortVideoDetectionCard(enabled: Boolean, todayCount: Int, onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.short_video_detection_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(if (enabled) R.string.short_video_accessibility_enabled else R.string.short_video_accessibility_disabled),
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.short_video_detection_body), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.short_video_setup_steps_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.short_video_setup_step_1), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.short_video_setup_step_2), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.short_video_setup_step_3), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.short_video_count_value, todayCount), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.short_video_accessibility_settings))
            }
        }
    }
}

private fun isShortVideoAccessibilityEnabled(context: android.content.Context): Boolean {
    val component = "${context.packageName}/com.snstimer.app.accessibility.ShortVideoAccessibilityService"
    val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabledServices?.split(':')?.any { it.equals(component, ignoreCase = true) } == true
}

@Composable
private fun AnalyticsMetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AppAnalyticsCard(
    app: InstalledApp,
    usage: com.snstimer.app.data.AppDailyUsage?,
) {
    val iconBitmap = remember(app.packageName) {
        app.icon.toBitmap(width = 72, height = 72).asImageBitmap()
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalyticsValue(
                    label = stringResource(R.string.analytics_today_short),
                    value = formatUsageDurationWithSeconds(usage?.todayMs ?: 0L),
                    modifier = Modifier.weight(1f),
                )
                AnalyticsValue(
                    label = stringResource(R.string.analytics_week_average_short),
                    value = formatUsageDurationWithSeconds(usage?.sevenDayAverageMs ?: 0L),
                    modifier = Modifier.weight(1f),
                )
                AnalyticsValue(
                    label = stringResource(R.string.analytics_month_average_short),
                    value = formatUsageDurationWithSeconds(usage?.thirtyDayAverageMs ?: 0L),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AnalyticsValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun formatUsageDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}시간 ${minutes}분" else "${minutes}분"
}

private fun formatUsageDurationWithSeconds(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}시간 ${minutes}분 ${seconds}초"
        minutes > 0L -> "${minutes}분 ${seconds}초"
        else -> "${seconds}초"
    }
}

private const val TIMER_TAB = 0
private const val ANALYTICS_TAB = 1

@Composable
private fun TargetAppsSection(
    apps: List<InstalledApp>,
    allApps: List<InstalledApp>,
    selectedApps: List<InstalledApp>,
    selectedPackages: Set<String>,
    appsLoading: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    val allSelected = allApps.isNotEmpty() && allApps.all { it.packageName in selectedPackages }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = stringResource(R.string.target_apps_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.target_apps_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.selected_count, selectedPackages.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            if (selectedApps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.selected_apps_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                SelectedAppsSummary(
                    apps = selectedApps,
                    onRemove = { packageName -> onToggle(packageName, false) },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(text = stringResource(R.string.search_apps)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null)
                },
                shape = RoundedCornerShape(12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = allApps.isNotEmpty()) { onSelectAll(!allSelected) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.select_all_apps),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.select_all_apps_summary, allApps.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                Checkbox(
                    checked = allSelected,
                    enabled = allApps.isNotEmpty(),
                    onCheckedChange = onSelectAll,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                appsLoading -> {
                    Text(
                        text = stringResource(R.string.target_apps_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                selected = app.packageName in selectedPackages,
                                onToggle = { checked -> onToggle(app.packageName, checked) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedAppsSummary(
    apps: List<InstalledApp>,
    onRemove: (String) -> Unit,
) {
    var expanded by remember(apps) { mutableStateOf(false) }
    val visibleApps = if (expanded) apps else apps.take(MAX_VISIBLE_SELECTED_APP_CHIPS)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        visibleApps.forEach { app ->
            val iconBitmap = remember(app.packageName) {
                app.icon.toBitmap(width = 48, height = 48).asImageBitmap()
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(50),
                modifier = Modifier.clickable { onRemove(app.packageName) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Image(
                        bitmap = iconBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.remove_selected_app, app.label),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        val remainingCount = apps.size - MAX_VISIBLE_SELECTED_APP_CHIPS
        if (remainingCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(50),
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Text(
                    text = if (expanded) {
                        stringResource(R.string.collapse_selected_apps)
                    } else {
                        stringResource(R.string.more_selected_apps, remainingCount)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private const val MAX_VISIBLE_SELECTED_APP_CHIPS = 8

@Composable
private fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val iconBitmap = remember(app.packageName) {
        app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!selected) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = iconBitmap,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = onToggle,
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    onOpenSettings: () -> Unit,
) {
    val statusColor by animateColorAsState(
        targetValue = if (granted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        label = "permissionStatusColor",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = statusColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (granted) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = statusColor,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                text = stringResource(
                                    if (granted) R.string.permission_granted else R.string.permission_required,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            if (!granted) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(text = stringResource(R.string.open_settings))
                }
            }
        }
    }
}
