package com.modspec.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.modspec.agent.ui.theme.ColorAccent
import com.modspec.agent.ui.theme.ColorStatusFail
import com.modspec.agent.ui.theme.ColorStatusFailBg
import com.modspec.agent.ui.theme.ColorStatusOk
import com.modspec.agent.ui.theme.ColorStatusOkBg
import com.modspec.agent.ui.theme.ColorStatusWarn
import com.modspec.agent.ui.theme.ColorStatusWarnBg
import com.modspec.agent.ui.theme.ModspecTheme
import io.github.libxposed.service.XposedService
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Port constants aligned with crates/modspec-protocol. */
object RpcPorts {
    const val HTTP = 8764
    const val WS = 8765
}

class MainActivity : ComponentActivity(), XposedServiceCoordinator.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = savedInstanceState?.getString(STATE_PAIRING_CODE) ?: generatePairingCode()
        MainActivity.pairingCode = code
        PairingStore.setPairingCode(this, code)

        setContent {
            ModspecTheme {
                AgentApp(
                    activity = this@MainActivity,
                    initialPairingCode = code,
                )
            }
        }

        AgentService.start(this)
    }

    override fun onStart() {
        super.onStart()
        XposedServiceCoordinator.addListener(this, notifyImmediately = true)
    }

    override fun onStop() {
        XposedServiceCoordinator.removeListener(this)
        super.onStop()
    }

    override fun onStateChanged(state: XposedServiceCoordinator.State, service: XposedService?) {
        if (!isFinishing) {
            MainActivity.signalRefresh(REFRESH_HOOK or REFRESH_ENV)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAIRING_CODE, MainActivity.pairingCode)
    }

    private fun generatePairingCode(): String =
        SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    companion object {
        private const val STATE_PAIRING_CODE = "pairing_code"
        private const val REFRESH_HOOK = 1
        private const val REFRESH_ENV = 2
        private val pendingRefresh = java.util.concurrent.atomic.AtomicInteger(0)

        @Volatile
        var pairingCode: String = ""
            private set

        fun signalRefresh(flags: Int) {
            pendingRefresh.getAndUpdate { it or flags }
        }

        fun consumeRefresh(): Int = pendingRefresh.getAndSet(0)

        val REFRESH_HOOK_VALUE = REFRESH_HOOK
        val REFRESH_ENV_VALUE = REFRESH_ENV
    }
}

private data class TopLevelTab(
    val label: String,
    val icon: ImageVector,
)

private val topTabs = listOf(
    TopLevelTab("快捷开关", Icons.Filled.Home),
    TopLevelTab("Hook 管理", Icons.Filled.Science),
    TopLevelTab("环境", Icons.Filled.AdminPanelSettings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentApp(
    activity: MainActivity,
    initialPairingCode: String,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }
    var pairingCode by remember { mutableStateOf(initialPairingCode) }
    var envLoading by remember { mutableStateOf(false) }
    var envReport by remember { mutableStateOf<EnvironmentChecker.Report?>(null) }
    var hookLoading by remember { mutableStateOf(false) }
    var hookSnapshot by remember { mutableStateOf<HookPanelSnapshot?>(null) }
    var togglesLoading by remember { mutableStateOf(false) }
    var toggles by remember { mutableStateOf<List<ShellToggleRow>>(emptyList()) }
    var primaryRunning by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun refreshEnvironment() {
        envLoading = true
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                EnvironmentChecker.run(activity, forceRefresh = true)
            }
            envReport = report
            envLoading = false
        }
    }

    fun refreshHookManager() {
        hookLoading = true
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                HookPanelLoader.load(activity)
            }
            hookSnapshot = snapshot
            toggles = snapshot.shellToggles
            togglesLoading = false
            hookLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshEnvironment()
        refreshHookManager()
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(3000)
            val pending = MainActivity.consumeRefresh()
            if (pending and MainActivity.Companion.REFRESH_HOOK_VALUE != 0) refreshHookManager()
            if (pending and MainActivity.Companion.REFRESH_ENV_VALUE != 0) refreshEnvironment()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(topTabs[selectedTab].label, fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                topTabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Crossfade(targetState = selectedTab, label = "tab") { tab ->
                when (tab) {
                    0 -> ShortcutsPage(
                        toggles = toggles,
                        loading = togglesLoading,
                        onToggleChanged = { toggle, enable ->
                            onShellToggleChanged(context, activity, toggle, enable, ::refreshHookManager)
                        },
                    )
                    1 -> HookPage(
                        snapshot = hookSnapshot,
                        loading = hookLoading,
                        primaryRunning = primaryRunning,
                        onPrimaryClick = {
                            if (!primaryRunning) {
                                primaryRunning = true
                                runPrimaryAction(context, activity) { primaryRunning = false }
                            }
                        },
                        onLogRefresh = ::refreshHookManager,
                    )
                    2 -> EnvPage(
                        report = envReport,
                        loading = envLoading,
                        pairingCode = pairingCode,
                        onRefresh = ::refreshEnvironment,
                        onCopyPairing = {
                            copyPairingCode(context, pairingCode)
                        },
                        onOpenLsposed = { openLsposedManager(context) },
                    )
                }
            }
        }
    }
}

private const val UNCATEGORIZED_KEY = "__uncategorized__"

/** 分组：同页可折叠；未分类固定最后。 */
private data class ToggleGroup(
    val key: String,
    val title: String,
    val toggles: List<ShellToggleRow>,
)

private fun groupToggles(toggles: List<ShellToggleRow>): List<ToggleGroup> {
    val byKey = LinkedHashMap<String, MutableList<ShellToggleRow>>()
    for (toggle in toggles) {
        val key = toggle.categoryTitles.joinToString("/")
            .ifBlank { UNCATEGORIZED_KEY }
        byKey.getOrPut(key) { mutableListOf() }.add(toggle)
    }
    val orderedKeys = byKey.keys.filter { it != UNCATEGORIZED_KEY } +
        listOfNotNull(UNCATEGORIZED_KEY.takeIf { byKey.containsKey(it) })
    return orderedKeys.map { key ->
        ToggleGroup(
            key = key,
            title = if (key == UNCATEGORIZED_KEY) "未分类" else key.replace("/", " / "),
            toggles = byKey.getValue(key),
        )
    }
}

@Composable
private fun ShortcutsPage(
    toggles: List<ShellToggleRow>,
    loading: Boolean,
    onToggleChanged: (ShellToggleRow, Boolean) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // 折叠状态会话内记忆（rememberSaveable）；默认全展开
    var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }

    val filtered = remember(toggles, query) {
        val q = query.trim()
        if (q.isEmpty()) toggles else toggles.filter { it.matchesQuery(q) }
    }
    val groups = remember(filtered) { groupToggles(filtered) }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "快捷开关",
                subtitle = "由 profile 声明的 shell 开关，独立于 Hook 规则",
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索开关（标题 / 描述 / 别名）") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                        }
                    }
                },
            )
        }
        when {
            loading && toggles.isEmpty() -> {
                item { LoadingRow() }
            }
            toggles.isEmpty() -> {
                item {
                    EmptyState("暂无已声明的快捷开关，请在 profile 中添加 shell_toggle mod")
                }
            }
            filtered.isEmpty() -> {
                item { EmptyState("无匹配开关") }
            }
            groups.size == 1 -> {
                // 只有一组：隐藏分组头完全平铺（与无分组时一致）
                items(groups[0].toggles, key = { it.id }) { toggle ->
                    ToggleCard(
                        toggle = toggle,
                        onToggleChanged = { checked -> onToggleChanged(toggle, checked) },
                    )
                }
            }
            else -> {
                groups.forEach { group ->
                    item(key = "group_${group.key}") {
                        ExpandableGroupCard(
                            group = group,
                            collapsed = group.key in collapsedGroups,
                            onToggleCollapsed = {
                                collapsedGroups =
                                    if (group.key in collapsedGroups) {
                                        collapsedGroups - group.key
                                    } else {
                                        collapsedGroups + group.key
                                    }
                            },
                            onToggleChanged = onToggleChanged,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandableGroupCard(
    group: ToggleGroup,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onToggleChanged: (ShellToggleRow, Boolean) -> Unit,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        label = "chevron",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleCollapsed)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${group.toggles.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (collapsed) "展开" else "折叠",
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation },
                )
            }
            if (!collapsed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    group.toggles.forEach { toggle ->
                        ToggleCard(
                            toggle = toggle,
                            onToggleChanged = { checked -> onToggleChanged(toggle, checked) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleCard(
    toggle: ShellToggleRow,
    onToggleChanged: (Boolean) -> Unit,
) {
    val preconditionBlocked = toggle.preconditionMet == false
    val appliedUnknown = toggle.appliedStatus == null
    // Switch checked 以 applied 为准；未知时保持 intent 位置（半透明）
    val checked = toggle.appliedStatus ?: toggle.persistedIntent

    val subtitle: String?
    val subtitleColor: Color
    when {
        preconditionBlocked -> {
            subtitle = toggle.requiresHint?.takeIf { it.isNotBlank() } ?: "前置条件未满足"
            subtitleColor = ColorStatusWarn
        }
        appliedUnknown && toggle.appliedStatusCommand != null -> {
            subtitle = "状态未知（查询失败）"
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        // 未配置状态查询命令时保持旧行为：不显示状态行
        appliedUnknown -> {
            subtitle = null
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
        toggle.appliedStatus == true && toggle.effectiveStatus != false -> {
            subtitle = "已开启"
            subtitleColor = ColorStatusOk
        }
        toggle.appliedStatus == true -> {
            subtitle = "已设置，待条件满足后生效"
            subtitleColor = ColorStatusWarn
        }
        else -> {
            subtitle = "未设置"
            subtitleColor = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = toggle.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (preconditionBlocked) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "前置条件未满足",
                            tint = ColorStatusWarn,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                toggle.description?.let { description ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                subtitle?.let { text ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = checked,
                onCheckedChange = onToggleChanged,
                enabled = !preconditionBlocked,
                modifier = Modifier.alpha(if (appliedUnknown || preconditionBlocked) 0.5f else 1f),
            )
        }
    }
}

@Composable
private fun HookPage(
    snapshot: HookPanelSnapshot?,
    loading: Boolean,
    primaryRunning: Boolean,
    onPrimaryClick: () -> Unit,
    onLogRefresh: () -> Unit,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "Hook 管理",
                subtitle = "查看已部署规则、运行进程与日志；一键软重启让 Hook 生效",
            )
        }
        if (loading && snapshot == null) {
            item { LoadingRow() }
        } else if (snapshot != null) {
            val running = primaryRunning

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "模块状态",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        StatusChip(
                            text = snapshot.serviceLabel,
                            color = if (snapshot.serviceConnected) ColorStatusOk else ColorStatusWarn,
                        )
                        Text(
                            text = if (snapshot.activeProfileId.isNullOrBlank()) {
                                "当前无已应用 profile"
                            } else {
                                "当前 profile：${snapshot.activeProfileId}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onPrimaryClick,
                            enabled = !running && snapshot.primaryAction != PrimaryAction.DISABLED,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Filled.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (snapshot.primaryAction) {
                                    PrimaryAction.SOFT_RESTART -> "软重启模块"
                                    PrimaryAction.RULES_ONLY -> "同步规则并重载"
                                    PrimaryAction.DISABLED -> "等待 LSPosed 连接"
                                },
                            )
                        }
                    }
                }
            }

            item {
                RuleSection(snapshot)
            }

            item {
                ProcessSection(snapshot)
            }

            item {
                LogSection(
                    logLines = snapshot.logLines,
                    onRefresh = onLogRefresh,
                )
            }
        }
    }
}

@Composable
private fun RuleSection(snapshot: HookPanelSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "已部署规则",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            if (snapshot.deployedRules.isEmpty()) {
                EmptyInline("暂无规则文件，请通过 CLI apply profile")
            } else {
                snapshot.deployedRules.forEach { rule ->
                    RuleCard(rule)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun RuleCard(rule: DeployedRuleRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = rule.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = if (rule.inActiveProfile) "生效中" else "未激活",
                    color = if (rule.inActiveProfile) ColorStatusOk else ColorStatusWarn,
                    container = if (rule.inActiveProfile) ColorStatusOkBg else ColorStatusWarnBg,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "目标：${rule.packages.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${rule.hookCount} 个 hook · 更新于 ${rule.updatedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessSection(snapshot: HookPanelSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "运行中的 Hook 进程",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            if (snapshot.runningProcesses.isEmpty()) {
                EmptyInline("暂无运行中的 Hook 进程")
            } else {
                snapshot.runningProcesses.forEach { proc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = proc.processName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        proc.uid?.let {
                            Text(
                                text = "uid $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogSection(
    logLines: List<String>,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "最近日志",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onRefresh,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("刷新")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (logLines.isEmpty()) {
                EmptyInline("暂无 Modspec 相关日志")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    logLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvPage(
    report: EnvironmentChecker.Report?,
    loading: Boolean,
    pairingCode: String,
    onRefresh: () -> Unit,
    onCopyPairing: () -> Unit,
    onOpenLsposed: () -> Unit,
) {
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = "环境检查",
                subtitle = "决定 profile 各 mod 能否生效",
            )
        }
        if (loading && report == null) {
            item { LoadingRow() }
        } else if (report != null) {
            val ok = report.items.count { it.status == EnvironmentChecker.Status.OK }
            val warn = report.items.count { it.status == EnvironmentChecker.Status.WARN }
            val fail = report.items.count { it.status == EnvironmentChecker.Status.FAIL }
            val scopeItem = report.items.firstOrNull { it.id == "module_scope" }
            val hasScopeIssue = scopeItem?.status == EnvironmentChecker.Status.FAIL ||
                (scopeItem?.status == EnvironmentChecker.Status.WARN &&
                    scopeItem.detail.contains("规则目标还需"))
            val hasBlocking = fail > 0

            item {
                EnvSummaryCard(
                    report = report,
                    ok = ok,
                    warn = warn,
                    fail = fail,
                    hasScopeIssue = hasScopeIssue,
                    hasBlocking = hasBlocking,
                    onOpenLsposed = onOpenLsposed,
                    loading = loading,
                )
            }

            item {
                PairingCard(
                    pairingCode = pairingCode,
                    onCopy = onCopyPairing,
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "逐项检查",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "决定 profile 各 mod 能否生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = onRefresh,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("刷新")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        report.items.forEach { item ->
                            EnvCheckCard(item)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvSummaryCard(
    report: EnvironmentChecker.Report,
    ok: Int,
    warn: Int,
    fail: Int,
    hasScopeIssue: Boolean,
    hasBlocking: Boolean,
    onOpenLsposed: () -> Unit,
    loading: Boolean,
) {
    val title: String
    val badgeText: String
    val badgeColor: Color
    val badgeContainer: Color
    when {
        hasBlocking -> {
            title = "有阻塞项待处理"
            badgeText = "阻塞"
            badgeColor = ColorStatusFail
            badgeContainer = ColorStatusFailBg
        }
        hasScopeIssue -> {
            title = "还需配置 Hook 作用域"
            badgeText = "注意"
            badgeColor = ColorStatusWarn
            badgeContainer = ColorStatusWarnBg
        }
        else -> {
            title = "可以联调"
            badgeText = "就绪"
            badgeColor = ColorStatusOk
            badgeContainer = ColorStatusOkBg
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    StatusBadge(
                        text = badgeText,
                        color = badgeColor,
                        container = badgeContainer,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "$ok 项通过 · $warn 项注意 · $fail 项失败",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasScopeIssue) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onOpenLsposed,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开 LSPosed 配置作用域")
                }
            }
        }
    }
}

@Composable
private fun PairingCard(
    pairingCode: String,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "PC 配对",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "在 PC 端执行 modspec-cli pair scan，输入下方 6 位码完成信任",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorAccent, RoundedCornerShape(16.dp))
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = pairingCode,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    ),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCopy,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("复制配对码")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "局域网端点",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            EndpointRow("http://<手机IP>:8764/health")
            Spacer(Modifier.height(8.dp))
            EndpointRow("ws://<手机IP>:8765/rpc")
        }
    }
}

@Composable
private fun EndpointRow(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Language,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EnvCheckCard(item: EnvironmentChecker.Item) {
    val (color, container) = when (item.status) {
        EnvironmentChecker.Status.OK -> ColorStatusOk to ColorStatusOkBg
        EnvironmentChecker.Status.WARN -> ColorStatusWarn to ColorStatusWarnBg
        EnvironmentChecker.Status.FAIL -> ColorStatusFail to ColorStatusFailBg
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = container.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = when (item.status) {
                        EnvironmentChecker.Status.OK -> "通过"
                        EnvironmentChecker.Status.WARN -> "注意"
                        EnvironmentChecker.Status.FAIL -> "失败"
                    },
                    color = color,
                    container = container,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.status != EnvironmentChecker.Status.OK) {
                item.why.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun EmptyState(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyInline(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    container: Color,
) {
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusChip(
    text: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .background(
                color.copy(alpha = 0.15f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun onShellToggleChanged(
    context: Context,
    activity: MainActivity,
    toggle: ShellToggleRow,
    enable: Boolean,
    onRefresh: () -> Unit,
) {
    if (!ShellRunner.canSu()) {
        Toast.makeText(context, "需要 root 权限", Toast.LENGTH_LONG).show()
        onRefresh()
        return
    }
    val command = if (enable) toggle.onCommand else toggle.offCommand
    if (command.isBlank()) {
        Toast.makeText(context, "该开关未配置命令", Toast.LENGTH_LONG).show()
        onRefresh()
        return
    }
    CoroutineScope(Dispatchers.IO).launch {
        // 点击前评估前置条件（实时查询）
        val preconditionMet = queryStatusChannel(
            rootAvailable = true,
            command = toggle.preconditionCommand,
            pattern = toggle.preconditionPattern,
        )
        if (preconditionMet == false) {
            val autoPrereq = toggle.autoPrereqCommand?.takeIf { it.isNotBlank() }
            if (autoPrereq == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        toggle.requiresHint?.takeIf { it.isNotBlank() } ?: "前置条件未满足",
                        Toast.LENGTH_LONG,
                    ).show()
                    onRefresh()
                }
                return@launch
            }
            val prereqResult = ShellRunner.runSu(autoPrereq)
            if (prereqResult.isFailure) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "自动补救执行失败：${prereqResult.exceptionOrNull()?.message ?: "unknown"}",
                        Toast.LENGTH_LONG,
                    ).show()
                    onRefresh()
                }
                return@launch
            }
        }
        val result = ShellRunner.runSu(command)
        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                persistShellToggleState(context, toggle.id, enable)
            } else {
                val error = result.exceptionOrNull()?.message ?: "unknown"
                Toast.makeText(
                    context,
                    "执行失败：$error",
                    Toast.LENGTH_LONG,
                ).show()
            }
            onRefresh()
        }
    }
}

private fun persistShellToggleState(context: Context, modId: String, on: Boolean) {
    val state = AgentStorage.readState(context)
    val toggles = state.optJSONObject("shell_toggle_state") ?: org.json.JSONObject()
    toggles.put(modId, on)
    state.put("shell_toggle_state", toggles)
    AgentStorage.writeState(context, state)
}

private fun runPrimaryAction(
    context: Context,
    activity: MainActivity,
    onDone: () -> Unit,
) {
    val snapshot = HookPanelLoader.load(context)
    when (snapshot.primaryAction) {
        PrimaryAction.SOFT_RESTART -> runHookAction(context, rulesOnly = false, onDone = onDone)
        PrimaryAction.RULES_ONLY -> runHookAction(context, rulesOnly = true, onDone = onDone)
        PrimaryAction.DISABLED -> {
            Toast.makeText(context, "请先启用模块并打开本 App 绑定 XposedService", Toast.LENGTH_LONG).show()
            onDone()
        }
    }
}

private fun runHookAction(
    context: Context,
    rulesOnly: Boolean,
    onDone: () -> Unit,
) {
    if (!rulesOnly && ModspecApp.xposedService == null) {
        Toast.makeText(context, "请先启用模块并打开本 App 绑定 XposedService", Toast.LENGTH_LONG).show()
        onDone()
        return
    }
    if (rulesOnly && !ShellRunner.canSu() && ModspecApp.xposedService == null) {
        Toast.makeText(context, "需要 root 或 XposedService 才能同步规则", Toast.LENGTH_LONG).show()
        onDone()
        return
    }
    CoroutineScope(Dispatchers.IO).launch {
        val result = if (rulesOnly) {
            ModuleReloader.reloadRules(context)
        } else {
            ModuleReloader.softRestart(context)
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            onDone()
        }
    }
}

private fun copyPairingCode(context: Context, code: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("pairing_code", code))
    Toast.makeText(context, "配对码已复制", Toast.LENGTH_SHORT).show()
}

private fun openLsposedManager(context: Context) {
    val packages = listOf("org.lsposed.manager", "io.github.lsposed.manager")
    val intent = packages.firstNotNullOfOrNull { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    if (intent != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "未找到 LSPosed Manager", Toast.LENGTH_SHORT).show()
    }
}
