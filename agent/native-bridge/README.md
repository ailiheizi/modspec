# ModSpec native hook bridge (ShadowHook)

In-process native PLT/export hook layer for the `native_hook` script capability.

## Contents

| File | Purpose |
|------|---------|
| `xh_bridge.c` | Our JNI bridge: per-hook AArch64 trampolines (save x0-x7 → call original → report to Java), `NativeHookBridge` JNI entry points |
| `shadowhook.h` | bytedance/android-inline-hook public header (Apache-2.0, vendored) |
| `libmodspec_native.so` | Prebuilt arm64-v8a (PC cache `~/.cache/modspec/libmodspec_native-arm64-v8a.so` is the deployed copy) |

## Build

Requires Android NDK. Rebuild arm64-v8a from a checkout of
`github.com/bytedance/android-inline-hook` (merge the shadowhook sources):

```bash
NDK=~/Library/Android/sdk/ndk/<ver>
CLANG=$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang
CPP=<android-inline-hook>/shadowhook/src/main/cpp
$CLANG -Os -fPIC -shared -std=gnu11 \
  -I. -I$CPP -I$CPP/include -I$CPP/arch/arm64 -I$CPP/common \
  -I$CPP/third_party/xdl -I$CPP/third_party/bsd -I$CPP/third_party/lss \
  -llog -o libmodspec_native.so \
  xh_bridge.c \
  $CPP/shadowhook.c $CPP/sh_elf.c $CPP/sh_enter.c $CPP/sh_hub.c \
  $CPP/sh_island.c $CPP/sh_linker.c $CPP/sh_recorder.c $CPP/sh_safe.c \
  $CPP/sh_switch.c $CPP/sh_task.c $CPP/sh_xdl.c \
  $CPP/common/bytesig.c $CPP/common/sh_errno.c $CPP/common/sh_log.c \
  $CPP/common/sh_ref.c $CPP/common/sh_trampo.c $CPP/common/sh_util.c \
  $CPP/third_party/xdl/xdl_iterate.c $CPP/third_party/xdl/xdl_linker.c \
  $CPP/arch/arm64/sh_a64.c $CPP/arch/arm64/sh_inst.c \
  $CPP/arch/arm64/sh_glue.S
```

(Exclude `sh_jni.c` — it defines its own `JNI_OnLoad`.)

## How it works

1. Agent (root) deploys the lib into `/data/local/tmp/modspec/frida/` on demand.
2. Hook processes `System.load` it; `JNI_OnLoad` initializes ShadowHook.
3. `modspec.nativeHook({ lib, symbol, id })` registers a trampoline via
   `shadowhook_hook_sym_name`; the trampoline saves the caller's x0-x7, runs
   the original function (observe mode), then reports to
   `NativeHookBridge.onHook` (structured `native_hit` event, result may be
   overridden).
