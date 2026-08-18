# Third-Party Notices

ModSpec is licensed under **AGPL-3.0-or-later** (see [LICENSE](LICENSE)). The
following third-party components are bundled or referenced, together with
their licenses and provenance. Nothing here is copied verbatim from a
proprietary or differently-licensed project unless noted.

## Runtime dependencies

| Component | Version | License | Purpose | Provenance |
|---|---|---|---|---|
| Mozilla Rhino | 1.7.15 | MPL-2.0 | JavaScript engine (agent) | Maven Central `org.mozilla:rhino`; file-level copyleft, compatible with AGPL |
| LuaJ | 3.0.1 | MIT | Lua 5.2 engine (agent) | Maven Central `org.luaj:luaj-jse` |
| libxposed API/service | 102.0.0 | Apache-2.0 | LSPosed hook framework binding (agent) | Maven Central `io.github.libxposed` |
| DexKit | 2.0.5 | Apache-2.0 | obfuscated-method lookup (agent) | Maven Central `org.luckypray:dexkit` |
| nanohttpd | 2.3.1 | MIT | embedded HTTP server (agent) | Maven Central `org.nanohttpd` |
| Java-WebSocket | 1.5.6 | MIT | WebSocket server (agent) | Maven Central `org.java-websocket` |
| tomlj | 1.1.1 | MIT | TOML parsing (agent) | Maven Central `org.tomlj` |
| JUnit | 4.12 | EPL-2.0 | agent unit tests (test scope) | Maven Central `junit:junit` |
| Rust crates | — | MIT/Apache-2.0 (dual) | serde/serde_json/thiserror/tokio/clap/reqwest/toml/chrono/uuid/tracing/tokio-tungstenite/futures-util/anyhow/sha2/directories/pretty_assertions | crates.io |

## Reference-only material (not linked into the build)

The following projects were studied for API design. **No source code was
copied verbatim; ModSpec's scripting runtime was reimplemented independently.**

| Project | Repository | License | What ModSpec took (concepts only) |
|---|---|---|---|
| LSPilot | github.com/YunJavaPro/LSPilot (+ `me.yun.lspilot` module, + LSPilot-Docs) | no explicit license — **reference only** | named hooks with replace/unhook ids, `before/after/replace` + `param.args/result/thisObject` shape, findClass/findClassOrNull + host classloader, DexKit builder-style queries, log/emit helpers, per-package scripting lifecycle |
| LuaHook | github.com/KuLiPai/LuaHook (+ LuaHook-Scripts, luahook-docs) | GPL-3.0 — **reference only, not incorporated** | `hook { class=…, method=…, before/after=function(it) … end }` table-style API and `it.thisObject`/`it.args` invocation context shape |
| JsHook | github.com/Xposed-Modules-Repo/me.jsonet.jshook | no explicit license — **reference only** | Rhino-on-Android as the JS engine choice; Java reflection ergonomics pitfalls (method overloads, wrapped strings) |
| ShadowHook (android-inline-hook) | github.com/bytedance/android-inline-hook | Apache-2.0 | in-process native PLT/inline hook backend for the `native_hook` capability (vendored header + compiled into libmodspec_native.so) |
| xhook (evaluated, replaced) | github.com/iqiyi/xhook | MIT | original PLT hook backend — replaced by ShadowHook due to Android 15 mprotect incompatibility |
| Frida / frida-tools | github.com/frida/frida | LGPL-2.1 (tools: MIT) | conceptual `replace/before/after` + `callOriginal` semantics, `send()`-style structured messages, load/unload lifecycle |
| XposedBridge / LSPosed | github.com/LSPosed/LSPosed | Apache-2.0 / GPL-3.0 | hook chain semantics background; ModSpec uses libxposed API 102 directly |

The local `references/` directory contains clones of LSPilot documentation
and module release artifacts used purely for study; they are not part of the
ModSpec build or distribution.

## History

- ModSpec was originally released under the MIT License
  (Copyright (c) 2026 ModSpec contributors). Effective with the scripting
  engine release it is relicensed to AGPL-3.0-or-later; the relicensing
  decision was made by the project maintainer.
- The MIT text remains applicable to any unmodified snapshot distributed
  under the old license; new releases are AGPL-3.0-or-later.
