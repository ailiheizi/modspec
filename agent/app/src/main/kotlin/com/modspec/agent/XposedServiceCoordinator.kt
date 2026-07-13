package com.modspec.agent

import io.github.libxposed.service.XposedService
import java.util.concurrent.CopyOnWriteArraySet

/** XposedService 绑定状态 — 对齐 HMA ServiceClient 体验层 + libxposed example App.kt。 */
object XposedServiceCoordinator {

    enum class State { DISCONNECTED, CONNECTED, DIED }

    fun interface Listener {
        fun onStateChanged(state: State, service: XposedService?)
    }

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener, notifyImmediately: Boolean = true) {
        listeners.add(listener)
        if (notifyImmediately) listener.onStateChanged(state, ModspecApp.xposedService)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun onServiceBind(service: XposedService) {
        state = State.CONNECTED
        dispatch(service)
    }

    fun onServiceDied() {
        state = State.DIED
        dispatch(null)
    }

    fun statusLabel(): String = when (state) {
        State.CONNECTED -> {
            val s = ModspecApp.xposedService
            if (s != null) "已连接 · ${s.frameworkName} API ${s.apiVersion}"
            else "已连接"
        }
        State.DIED -> "连接已断开，请重新打开 App"
        State.DISCONNECTED -> "等待 LSPosed 绑定…"
    }

    private fun dispatch(service: XposedService?) {
        listeners.forEach { it.onStateChanged(state, service) }
    }
}
