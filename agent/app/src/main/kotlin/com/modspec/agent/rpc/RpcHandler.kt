package com.modspec.agent.rpc

import android.content.Context
import com.modspec.agent.AppProfileApplier
import com.modspec.agent.EnvironmentChecker
import com.modspec.agent.AgentStorage
import com.modspec.agent.LogTailReader
import com.modspec.agent.ModuleReloader
import com.modspec.agent.PairingStore
import com.modspec.agent.ModspecApp
import com.modspec.agent.ShellRunner
import org.json.JSONObject
import java.util.UUID

/**
 * JSON-RPC 2.0 handler for modspec-cli ↔ agent transport.
 */
class RpcHandler(context: Context) {

    private val appContext = context.applicationContext
    private val profileApplier = AppProfileApplier(appContext)

    fun handleRequest(body: String): String {
        val request = JSONObject(body)
        val id = request.optString("id", UUID.randomUUID().toString())
        val method = request.optString("method")
        val params = request.optJSONObject("params") ?: JSONObject()

        return try {
            val result = when (method) {
                METHOD_PING -> JSONObject().put("pong", true)
                METHOD_GET_STATUS -> getStatus()
                METHOD_APPLY_PROFILE -> applyProfile(params)
                METHOD_TOGGLE_MOD -> toggleMod(params)
                METHOD_VERIFY -> verify(params)
                METHOD_REAPPLY -> reapply(params)
                METHOD_COLLECT_LOGS -> collectLogs(params)
                METHOD_SOFT_RESTART -> softRestart(params)
                else -> throw RpcException(-32601, "Method not found: $method")
            }
            jsonRpcResult(id, result)
        } catch (error: RpcException) {
            jsonRpcError(id, error.code, error.message)
        } catch (error: Exception) {
            jsonRpcError(id, -32603, error.message ?: "Internal error")
        }
    }

    fun handlePair(body: String): String {
        val request = JSONObject(body)
        val code = request.optString("code")
        if (!PairingStore.validateCode(appContext, code)) {
            return JSONObject()
                .put("error", "invalid pairing code")
                .toString()
        }
        return JSONObject()
            .put("request_id", UUID.randomUUID().toString())
            .put("device_id", PairingStore.getOrCreateDeviceId(appContext))
            .put("device_name", PairingStore.getDeviceName(appContext))
            .put("model", android.os.Build.MODEL)
            .put("android_version", android.os.Build.VERSION.SDK_INT)
            .toString()
    }

    fun reapplyFromIntent(onlyFailed: Boolean): String {
        val jobId = profileApplier.reapply(onlyFailed)
        return JSONObject().put("job_id", jobId).toString()
    }

    private fun getStatus(): JSONObject {
        val state = AgentStorage.readState(appContext)
        val env = EnvironmentChecker.run(appContext)
        return JSONObject()
            .put("device_id", PairingStore.getOrCreateDeviceId(appContext))
            .put("model", android.os.Build.MODEL)
            .put("android_version", android.os.Build.VERSION.SDK_INT)
            .put("agent_version", "0.1.0")
            .put("root_available", ShellRunner.canSu())
            .put("xposed_service_bound", ModspecApp.xposedService != null)
            .put("environment", env.toJsonArray())
            .put("state", state)
    }

    private fun applyProfile(params: JSONObject): JSONObject {
        val profile = params.getJSONObject("profile")
        val dryRun = params.optBoolean("dry_run", false)
        val jobId = profileApplier.applyFromJson(profile, dryRun)
        return JSONObject().put("job_id", jobId)
    }

    private fun reapply(params: JSONObject): JSONObject {
        val onlyFailed = params.optBoolean("only_failed", false)
        val jobId = profileApplier.reapply(onlyFailed)
        return JSONObject().put("job_id", jobId)
    }

    private fun toggleMod(params: JSONObject): JSONObject {
        val modId = params.getString("mod_id")
        val enabled = params.getBoolean("enabled")
        val state = AgentStorage.readState(appContext)
        val items = state.optJSONObject("items") ?: JSONObject()
        val item = items.optJSONObject(modId) ?: JSONObject()
        item.put("enabled", enabled)
        items.put(modId, item)
        state.put("items", items)
        AgentStorage.writeState(appContext, state)
        return JSONObject().put("ok", true)
    }

    private fun verify(@Suppress("UNUSED_PARAMETER") params: JSONObject): JSONObject =
        JSONObject().put("drift", org.json.JSONArray())

    private fun collectLogs(@Suppress("UNUSED_PARAMETER") params: JSONObject): JSONObject {
        val lines = LogTailReader.tail(30)
        val array = org.json.JSONArray()
        lines.forEach { array.put(it) }
        return JSONObject().put("lines", array)
    }

    private fun softRestart(params: JSONObject): JSONObject {
        val rulesOnly = params.optBoolean("rules_only", false)
        val result = if (rulesOnly) {
            ModuleReloader.reloadRules(appContext)
        } else {
            ModuleReloader.softRestart(appContext)
        }
        return JSONObject()
            .put("hot_reload_ok", result.hotReloadOk)
            .put("hot_reload_failed", result.hotReloadFailed)
            .put("hot_reload_unsupported", result.hotReloadUnsupported)
            .put("running_targets", result.runningTargets)
            .put("restarted_packages", org.json.JSONArray(result.restartedPackages))
            .put("message", result.message)
    }

    private fun jsonRpcResult(id: String, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("result", result)
            .toString()

    private fun jsonRpcError(id: String, code: Int, message: String): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
            .toString()

    private class RpcException(val code: Int, override val message: String) : Exception(message)

    companion object {
        const val HTTP_PORT = 8764
        const val WS_PORT = 8765

        const val METHOD_PING = "ping"
        const val METHOD_GET_STATUS = "get_status"
        const val METHOD_APPLY_PROFILE = "apply_profile"
        const val METHOD_TOGGLE_MOD = "toggle_mod"
        const val METHOD_VERIFY = "verify"
        const val METHOD_REAPPLY = "reapply"
        const val METHOD_COLLECT_LOGS = "collect_logs"
        const val METHOD_SOFT_RESTART = "soft_restart"
    }
}
