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
        RuleEngine.kt          # .rule.toml → hook chain (TODO)
        ProfileApplier.kt        # .mspec.toml mods (TODO)
        AgentService.kt        # Foreground + HTTP/WS servers
        MainActivity.kt        # 配对码 + Hook 管家面板（规则/进程/日志/一键重载）
        BootReceiver.kt        # Boot reapply
        rpc/
          RpcHandler.kt        # JSON-RPC 2.0 dispatch
          LocalHttpServer.kt   # NanoHTTPD :8764
          LocalWsServer.kt     # WebSocket :8765 stub
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

./gradlew :app:assembleDebug    # Linux/macOS
gradlew.bat :app:assembleDebug  # Windows
```

`gradle.properties` 可设置 `org.gradle.java.home` 指向 JDK 17，避免默认 JDK 25 导致 `IllegalArgumentException: 25.0.3`。

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install & enable

1. Install APK, enable module in LSPosed Manager.
2. Scope is static (`scope.list`): only **`system`（系统框架）** is declared. Target apps come from the applied **profile** (`required_scope` in state), not hardcoded OEM packages. Set `staticScope=false` so Manager can add apps beyond the list.
3. Reboot or soft-restart target apps after first enable.

## RPC (skeleton)

| Channel | Port | Status |
|---------|------|--------|
| HTTP `/health` | 8764 | NanoHTTPD stub |
| WebSocket `/rpc` | 8765 | TODO stub |
| JSON-RPC methods | — | `RpcHandler` returns placeholder results |

See `docs/protocol.md` and `crates/modspec-protocol`.

## Notes

- `libxposed:api:102.0.0` is **compileOnly** — supplied by LSPosed at runtime.
- Release builds enable R8; `proguard-rules.pro` keeps `XposedModule` entries and rewrites `java_init.list`.
- `usesCleartextTraffic=true` for local LAN HTTP during development; switch to TLS for production pairing.
- `RuleEngine` 通过 libxposed `openRemoteFile` + `RemotePreferences.rules_generation` 跨进程加载规则；无 Service 时降级 `/data/local/tmp/modspec/rules/`。
- Hook 管家 UI：已部署规则列表、运行进程、`logcat` 尾行、单一主操作按钮（软重启或仅同步规则）。
- 参考实现见仓库根目录 `references/INTEGRATION.md`。
