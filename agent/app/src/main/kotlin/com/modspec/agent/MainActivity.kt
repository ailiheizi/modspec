package com.modspec.agent

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import io.github.libxposed.service.XposedService
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Pairing UI + environment diagnostics + Hook 管家面板。
 */
class MainActivity : Activity(), XposedServiceCoordinator.Listener {

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var checksContainer: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var summaryTitle: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var summaryBadge: TextView
    private lateinit var openLsposedButton: Button
    private lateinit var hookServiceStatus: TextView
    private lateinit var hookProfile: TextView
    private lateinit var hookRulesList: LinearLayout
    private lateinit var hookRulesEmpty: TextView
    private lateinit var hookProcessList: LinearLayout
    private lateinit var hookProcessEmpty: TextView
    private lateinit var hookLogTail: TextView
    private lateinit var hookPrimaryButton: Button
    private lateinit var hookLogRefreshButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val code = savedInstanceState?.getString(STATE_PAIRING_CODE) ?: generatePairingCode()
        PairingStore.setPairingCode(this, code)
        findViewById<TextView>(R.id.pairing_code).text = code
        findViewById<TextView>(R.id.endpoint_http).text =
            getString(R.string.endpoint_http_fmt, RpcPorts.HTTP)
        findViewById<TextView>(R.id.endpoint_ws).text =
            getString(R.string.endpoint_ws_fmt, RpcPorts.WS)

        checksContainer = findViewById(R.id.env_checks)
        progress = findViewById(R.id.env_progress)
        summaryTitle = findViewById(R.id.env_summary_title)
        summaryDetail = findViewById(R.id.env_summary_detail)
        summaryBadge = findViewById(R.id.env_summary_badge)
        openLsposedButton = findViewById(R.id.btn_open_lsposed)
        hookServiceStatus = findViewById(R.id.hook_service_status)
        hookProfile = findViewById(R.id.hook_profile)
        hookRulesList = findViewById(R.id.hook_rules_list)
        hookRulesEmpty = findViewById(R.id.hook_rules_empty)
        hookProcessList = findViewById(R.id.hook_process_list)
        hookProcessEmpty = findViewById(R.id.hook_process_empty)
        hookLogTail = findViewById(R.id.hook_log_tail)
        hookPrimaryButton = findViewById(R.id.btn_hook_primary)
        hookLogRefreshButton = findViewById(R.id.btn_hook_log_refresh)

        findViewById<Button>(R.id.env_refresh).setOnClickListener { refreshEnvironmentChecks() }
        findViewById<Button>(R.id.btn_copy_pairing).setOnClickListener { copyPairingCode() }
        openLsposedButton.setOnClickListener { openLsposedManager() }
        hookPrimaryButton.setOnClickListener { runPrimaryAction() }
        hookLogRefreshButton.setOnClickListener { refreshHookManager() }

        AgentService.start(this)
        refreshHookManager()
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
        runOnUiThread {
            if (!isFinishing) {
                refreshHookManager()
                if (service != null) refreshEnvironmentChecks()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironmentChecks()
        refreshHookManager()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAIRING_CODE, findViewById<TextView>(R.id.pairing_code).text.toString())
    }

    private fun refreshEnvironmentChecks() {
        progress.visibility = View.VISIBLE
        summaryTitle.setText(R.string.env_summary_loading)
        summaryDetail.text = ""
        worker.execute {
            val report = EnvironmentChecker.run(this, forceRefresh = true)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderSummary(report)
                renderChecks(report)
                progress.visibility = View.GONE
            }
        }
    }

    private fun renderSummary(report: EnvironmentChecker.Report) {
        val ok = report.items.count { it.status == EnvironmentChecker.Status.OK }
        val warn = report.items.count { it.status == EnvironmentChecker.Status.WARN }
        val fail = report.items.count { it.status == EnvironmentChecker.Status.FAIL }
        val scopeItem = report.items.firstOrNull { it.id == "module_scope" }
        val hasScopeIssue = scopeItem?.status == EnvironmentChecker.Status.FAIL ||
            (scopeItem?.status == EnvironmentChecker.Status.WARN &&
                scopeItem.detail.contains("规则目标还需"))
        val hasBlocking = fail > 0

        when {
            hasBlocking -> {
                summaryTitle.setText(R.string.env_summary_blocked)
                summaryBadge.setText(R.string.env_badge_fail)
                summaryBadge.setBackgroundResource(R.drawable.bg_badge_fail)
                summaryBadge.setTextColor(getColor(R.color.status_fail))
            }
            hasScopeIssue -> {
                summaryTitle.setText(R.string.env_summary_scope)
                summaryBadge.setText(R.string.env_badge_warn)
                summaryBadge.setBackgroundResource(R.drawable.bg_badge_warn)
                summaryBadge.setTextColor(getColor(R.color.status_warn))
            }
            else -> {
                summaryTitle.setText(R.string.env_summary_ready)
                summaryBadge.setText(R.string.env_badge_ok)
                summaryBadge.setBackgroundResource(R.drawable.bg_badge_ok)
                summaryBadge.setTextColor(getColor(R.color.status_ok))
            }
        }

        summaryDetail.text = getString(R.string.env_summary_counts, ok, warn, fail)
        openLsposedButton.visibility =
            if (hasScopeIssue) View.VISIBLE else View.GONE
    }

    private fun renderChecks(report: EnvironmentChecker.Report) {
        checksContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)
        report.items.forEach { item ->
            val row = inflater.inflate(R.layout.item_env_check, checksContainer, false)
            bindCheckRow(row, item)
            checksContainer.addView(row)
        }
    }

    private fun bindCheckRow(row: View, item: EnvironmentChecker.Item) {
        val root = row.findViewById<LinearLayout>(R.id.env_item_root)
        val badge = row.findViewById<TextView>(R.id.env_badge)
        val whyView = row.findViewById<TextView>(R.id.env_why)
        val hintView = row.findViewById<TextView>(R.id.env_hint)

        row.findViewById<TextView>(R.id.env_title).text = item.title
        row.findViewById<TextView>(R.id.env_detail).text = item.detail

        when (item.status) {
            EnvironmentChecker.Status.OK -> {
                root.setBackgroundResource(R.drawable.bg_env_strip_ok)
                badge.setText(R.string.env_item_ok)
                badge.setBackgroundResource(R.drawable.bg_badge_ok)
                badge.setTextColor(getColor(R.color.status_ok))
                whyView.visibility = View.GONE
                hintView.visibility = View.GONE
            }
            EnvironmentChecker.Status.WARN -> {
                root.setBackgroundResource(R.drawable.bg_env_strip_warn)
                badge.setText(R.string.env_item_warn)
                badge.setBackgroundResource(R.drawable.bg_badge_warn)
                badge.setTextColor(getColor(R.color.status_warn))
                whyView.visibility = View.VISIBLE
                whyView.text = item.why
                if (item.hint.isNullOrBlank()) {
                    hintView.visibility = View.GONE
                } else {
                    hintView.visibility = View.VISIBLE
                    hintView.text = item.hint
                }
            }
            EnvironmentChecker.Status.FAIL -> {
                root.setBackgroundResource(R.drawable.bg_env_strip_fail)
                badge.setText(R.string.env_item_fail)
                badge.setBackgroundResource(R.drawable.bg_badge_fail)
                badge.setTextColor(getColor(R.color.status_fail))
                whyView.visibility = View.VISIBLE
                whyView.text = item.why
                if (item.hint.isNullOrBlank()) {
                    hintView.visibility = View.GONE
                } else {
                    hintView.visibility = View.VISIBLE
                    hintView.text = item.hint
                }
            }
        }
    }

    private fun copyPairingCode() {
        val code = findViewById<TextView>(R.id.pairing_code).text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pairing_code", code))
        Toast.makeText(this, R.string.pairing_copied, Toast.LENGTH_SHORT).show()
    }

    private fun refreshHookManager() {
        worker.execute {
            val snapshot = HookPanelLoader.load(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                renderHookManager(snapshot)
            }
        }
    }

    private fun renderHookManager(snapshot: HookPanelSnapshot) {
        hookServiceStatus.text = snapshot.serviceLabel
        hookProfile.text = if (snapshot.activeProfileId.isNullOrBlank()) {
            getString(R.string.hook_active_profile_none)
        } else {
            getString(R.string.hook_active_profile_fmt, snapshot.activeProfileId)
        }

        val inflater = LayoutInflater.from(this)
        hookRulesList.removeAllViews()
        if (snapshot.deployedRules.isEmpty()) {
            hookRulesEmpty.visibility = View.VISIBLE
        } else {
            hookRulesEmpty.visibility = View.GONE
            snapshot.deployedRules.forEach { rule ->
                val row = inflater.inflate(R.layout.item_hook_rule, hookRulesList, false)
                row.findViewById<TextView>(R.id.hook_rule_name).text = rule.displayName
                val badge = row.findViewById<TextView>(R.id.hook_rule_badge)
                if (rule.inActiveProfile) {
                    badge.setText(R.string.hook_rule_active)
                    badge.setBackgroundResource(R.drawable.bg_badge_ok)
                    badge.setTextColor(getColor(R.color.status_ok))
                } else {
                    badge.setText(R.string.hook_rule_idle)
                    badge.setBackgroundResource(R.drawable.bg_badge_warn)
                    badge.setTextColor(getColor(R.color.status_warn))
                }
                row.findViewById<TextView>(R.id.hook_rule_packages).text =
                    getString(R.string.hook_rule_packages_fmt, rule.packages.joinToString(", "))
                row.findViewById<TextView>(R.id.hook_rule_meta).text =
                    getString(R.string.hook_rule_meta_fmt, rule.hookCount, rule.updatedAt)
                hookRulesList.addView(row)
            }
        }

        hookProcessList.removeAllViews()
        if (snapshot.runningProcesses.isEmpty()) {
            hookProcessEmpty.visibility = View.VISIBLE
        } else {
            hookProcessEmpty.visibility = View.GONE
            snapshot.runningProcesses.forEach { proc ->
                val row = inflater.inflate(R.layout.item_hook_process, hookProcessList, false)
                row.findViewById<TextView>(R.id.hook_process_name).text = proc.processName
                row.findViewById<TextView>(R.id.hook_process_uid).text =
                    proc.uid?.let { getString(R.string.hook_process_uid_fmt, it) } ?: ""
                hookProcessList.addView(row)
            }
        }

        hookLogTail.text = if (snapshot.logLines.isEmpty()) {
            getString(R.string.hook_log_empty)
        } else {
            snapshot.logLines.joinToString("\n")
        }

        when (snapshot.primaryAction) {
            PrimaryAction.SOFT_RESTART -> {
                hookPrimaryButton.isEnabled = true
                hookPrimaryButton.setText(R.string.btn_hook_primary_restart)
            }
            PrimaryAction.RULES_ONLY -> {
                hookPrimaryButton.isEnabled = true
                hookPrimaryButton.setText(R.string.btn_hook_primary_rules)
            }
            PrimaryAction.DISABLED -> {
                hookPrimaryButton.isEnabled = false
                hookPrimaryButton.setText(R.string.btn_hook_primary_disabled)
            }
        }
    }

    private fun runPrimaryAction() {
        val snapshot = HookPanelLoader.load(this)
        when (snapshot.primaryAction) {
            PrimaryAction.SOFT_RESTART -> runHookAction(rulesOnly = false)
            PrimaryAction.RULES_ONLY -> runHookAction(rulesOnly = true)
            PrimaryAction.DISABLED -> {
                Toast.makeText(this, R.string.hook_restart_need_service, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runHookAction(rulesOnly: Boolean) {
        if (!rulesOnly && ModspecApp.xposedService == null) {
            Toast.makeText(this, R.string.hook_restart_need_service, Toast.LENGTH_LONG).show()
            return
        }
        if (rulesOnly && !ShellRunner.canSu() && ModspecApp.xposedService == null) {
            Toast.makeText(this, R.string.hook_reload_need_root, Toast.LENGTH_LONG).show()
            return
        }
        hookPrimaryButton.isEnabled = false
        hookPrimaryButton.setText(R.string.hook_restart_running)
        worker.execute {
            val result = if (rulesOnly) {
                ModuleReloader.reloadRules(this)
            } else {
                ModuleReloader.softRestart(this)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                refreshHookManager()
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openLsposedManager() {
        val packages = listOf("org.lsposed.manager", "io.github.lsposed.manager")
        val intent = packages.firstNotNullOfOrNull { pkg ->
            packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (intent != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.lsposed_manager_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun generatePairingCode(): String =
        SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')

    companion object {
        private const val STATE_PAIRING_CODE = "pairing_code"
    }
}

/** Port constants aligned with crates/modspec-protocol. */
object RpcPorts {
    const val HTTP = 8764
    const val WS = 8765
}
