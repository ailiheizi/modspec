# ModSpec Agent (LSPosed module)

Kotlin LSPosed module using **libxposed API 102** (`compileOnly`).

## Layout

```text
agent/
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  app/
    build.gradle.kts
    src/main/
      kotlin/com/modspec/agent/
        ModspecModule.kt       # XposedModule entry (API 101+)
        RuleEngine.kt          # .rule.toml → hook chain + structured events
        HookRegistry.kt        # shared per-method hook multiplexer (rules + scripts)
        ScriptEngine.kt        # process-side JS/Lua script runner (RemoteFile zip)
        ScriptHost.kt          # per-process script host: class resolution, events, limits
        ScriptBridge.kt        # engine-agnostic script API (hook/findClass/fields/…)
        ScriptRuntime.kt       # RhinoRuntime (JS) + LuaRuntime (LuaJ) adapters
        ScriptManager.kt       # agent-side script RPCs: deploy/enable/disable/remove/reload
        ScriptManifest.kt      # manifest parse/validate + deterministic zip/hash codecs
        ScriptStateStore.kt    # persisted lifecycle state (hash/generation/hit/error)
        AppProfileApplier.kt   # .mspec.toml mods
        AgentService.kt        # Foreground + HTTP/WS servers
        MainActivity.kt        # 配对码 + Hook 管家面板（规则/进程/日志/一键重载）
        BootReceiver.kt        # Boot reapply
        rpc/
          RpcHandler.kt        # JSON-RPC 2.0 dispatch
          LocalHttpServer.kt   # NanoHTTPD :8764
          LocalWsServer.kt     # WebSocket :8765 JSON-RPC
      resources/META-INF/xposed/
        module.prop
        scope.list             # system + com.modspec.agent
        java_init.list
```

## Build

Requirements: **JDK 17+**（不要用 JDK 25 跑 Gradle，Kotlin 1.9 尚不支持）, Android SDK (API 35), `ANDROID_HOME` set.

```bash
cd agent
# Windows 示例（代理 + JDK 17）:
# $env:HTTP_PROXY="http://127.0.0.1:7890"; $env:HTTPS_PROXY="http://127.0.0.1:7890"
# $env:JAVA_HOME="C:\Users\26617\scoop\apps\temurin17-jdk\current"

bash ./gradlew :app:assembleDebug  # Linux/macOS
gradlew.bat :app:assembleDebug  # Windows
```

`gradle.properties` 可设置 `org.gradle.java.home` 指向 JDK 17，避免默认 JDK 25 导致 `IllegalArgumentException: 25.0.3`。

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install & enable

1. Install APK, enable module in LSPosed Manager.
2. Scope is static (`scope.list`): only **`system`（系统框架）** is declared. Target apps come from the applied **profile** (`required_scope` in state), not hardcoded OEM packages. Set `staticScope=false` so Manager can add apps beyond the list.
3. Reboot or soft-restart target apps after first enable.

## RPC

| Channel | Port | Status |
|---------|------|--------|
| HTTP `/health`, `/pair`, `/rpc` | 8764 | 主路径，loopback only |
| WebSocket `/rpc` | 8765 | text JSON-RPC，loopback only |
| JSON-RPC methods | — | profile apply、rule deploy、script deploy/list/enable/disable/remove/reload、restart、structured logs |

See `docs/protocol.md` and `crates/modspec-protocol`.

## Notes

- `libxposed:api:102.0.0` is **compileOnly** — supplied by LSPosed at runtime.
- JS/Lua engines: **Rhino 1.7.15** (MPL-2.0) and **LuaJ 3.0.1** (MIT) — pure Java, no native ABI.
- Release builds enable R8; `proguard-rules.pro` keeps `XposedModule` entries and rewrites `java_init.list`.
- `usesCleartextTraffic=true` for local LAN HTTP during development; switch to TLS for production pairing.
- `RuleEngine` / `ScriptEngine` 通过 libxposed `openRemoteFile` + `RemotePreferences.rules_generation` / `scripts_generation` 跨进程加载；无 Service 时降级 `/data/local/tmp/modspec/rules/` 与 `/data/local/tmp/modspec/scripts/`。
- 脚本包 = `manifest.toml` + `src/main.js|.lua`，以确定性 zip 分发（`scripts/<id>.zip`），PC/Agent 双侧校验。
- Hook 管家 UI：已部署规则列表、运行进程、`logcat` 尾行、单一主操作按钮（软重启或仅同步规则）。
- 参考实现见仓库根目录 `references/INTEGRATION.md`。
