package com.snstimer.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.snstimer.app.data.DailyUsageReport
import com.snstimer.app.data.DailyUsageStore
import com.snstimer.app.data.InstalledApp
import com.snstimer.app.data.InstalledAppsRepository
import com.snstimer.app.data.TargetAppsStore
import com.snstimer.app.overlay.OverlayTimerService
import com.snstimer.app.permission.PermissionChecker
import com.snstimer.app.ui.theme.SnsTimerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

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

    var overlayGranted by remember { mutableStateOf(PermissionChecker.canDrawOverlays(context)) }
    var usageGranted by remember { mutableStateOf(PermissionChecker.hasUsageStatsAccess(context)) }
    var monitoring by remember { mutableStateOf(OverlayTimerService.isRunning()) }
    var selectedPackages by remember { mutableStateOf(targetStore.getSelectedPackages()) }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    var statsRefresh by remember { mutableStateOf(0) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Service can still start; notification may be limited */ }

    fun refreshState() {
        overlayGranted = PermissionChecker.canDrawOverlays(context)
        usageGranted = PermissionChecker.hasUsageStatsAccess(context)
        monitoring = OverlayTimerService.isRunning()
        selectedPackages = targetStore.getSelectedPackages()
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
        if (selectedTab == ANALYTICS_TAB) {
            while (true) {
                delay(1_000L)
                statsRefresh++
            }
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

    fun togglePackage(packageName: String, selected: Boolean) {
        targetStore.setSelected(packageName, selected)
        selectedPackages = targetStore.getSelectedPackages()
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
                    onClick = { selectedTab = ANALYTICS_TAB },
                    text = { Text(stringResource(R.string.analytics_tab)) },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (selectedTab == TIMER_TAB) {
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

                if (allGranted) {
                    Spacer(modifier = Modifier.height(20.dp))
                    TargetAppsSection(
                        apps = filteredApps,
                        selectedApps = selectedApps,
                        selectedPackages = selectedPackages,
                        appsLoading = appsLoading,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onToggle = ::togglePackage,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                UsageAnalyticsSection(
                    report = usageReport,
                    apps = selectedApps,
                    selectedCount = selectedPackages.size,
                )
            }
        }
    }
}

@Composable
private fun UsageAnalyticsSection(
    report: DailyUsageReport,
    apps: List<InstalledApp>,
    selectedCount: Int,
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
    selectedApps: List<InstalledApp>,
    selectedPackages: Set<String>,
    appsLoading: Boolean,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
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
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        apps.forEach { app ->
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
    }
}

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
