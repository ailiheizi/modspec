// Native hook demo: hooks libc.getpid through the on-demand xhook bridge.
// Original behavior is preserved; each hit emits a structured native_hit event.
modspec.nativeHook({ lib: "libc.so", symbol: "getpid", id: "demo-getpid" });
modspec.emit("native_ready", { target: "com.ChillyRoom.DungeonShooter" });
