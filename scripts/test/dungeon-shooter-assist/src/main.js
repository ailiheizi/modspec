// ModSpec game-assist demo — visible in-game effect.
//
// Hooks UnityPlayerActivity lifecycle in DungeonShooter (Unity shell):
// - onResume: keep the screen on (FLAG_KEEP_SCREEN_ON) and enter immersive
//   sticky mode (hidden status/navigation bars)
// - onPause:  release the keep-screen-on flag
// Every fired hook shows an in-game Toast via ctx.toast (receiver context).

var ACTIVITY = "com.chillyroomsdk.sdkbridge.BasePlayerActivity"; // overrides onResume/onPause; UnityPlayerActivity's methods are only reached via invokespecial (not intercepted)
var FLAG_KEEP_SCREEN_ON = 128; // WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON (0x80)
var IMMERSIVE_STICKY = 0x1706; // IMMERSIVE_STICKY|HIDE_NAVIGATION|FULLSCREEN|LAYOUT_STABLE|LAYOUT_HIDE_NAVIGATION|LAYOUT_FULLSCREEN
var installed = 0;

function applyScreenFlags(ctx, keep) {
  try {
    var activity = ctx.thisObject;
    var window = modspec.callMethod(activity, "getWindow");
    if (window == null) return;
    if (keep) {
      modspec.callMethod(window, "addFlags", FLAG_KEEP_SCREEN_ON);
      var decor = modspec.callMethod(window, "getDecorView");
      if (decor != null) {
        modspec.callMethod(decor, "setSystemUiVisibility", IMMERSIVE_STICKY);
      }
      ctx.toast("ModSpec assist: 屏幕常亮 + 沉浸模式");
      modspec.emit("assist_active", { mode: "keep_screen_on+immersive" });
    } else {
      modspec.callMethod(window, "clearFlags", FLAG_KEEP_SCREEN_ON);
      ctx.toast("ModSpec assist: 已恢复");
    }
  } catch (e) {
    modspec.log("assist flags error:", String(e));
  }
}

function install(clazz, label) {
  var params = [];
  var m = modspec.findMethodOrNull(clazz, "onResume", params);
  if (m != null) {
    modspec.hook({
      clazz: clazz, method: "onResume", params: params,
      before: function(ctx) { applyScreenFlags(ctx, true); },
      id: "assist-onResume"
    });
    installed++;
  }
  m = modspec.findMethodOrNull(clazz, "onPause", params);
  if (m != null) {
    modspec.hook({
      clazz: clazz, method: "onPause", params: params,
      before: function(ctx) { applyScreenFlags(ctx, false); },
      id: "assist-onPause"
    });
    installed++;
  }
  modspec.log("hooks installed for", label, ":", installed);
}

function main() {
  // Process-start hook: proves the script's hooks fire (onCreate always runs).
  var app = modspec.findClassOrNull("com.chillyroomsdk.sdkbridge.BasePlayerApplication");
  if (app != null) {
    var onCreate = modspec.findMethodOrNull(app, "onCreate", []);
    if (onCreate != null) {
      modspec.hook({
        clazz: app, method: "onCreate", params: [],
        before: function(ctx) {
          ctx.toast("ModSpec assist active");
          modspec.emit("assist_boot", {});
        },
        id: "assist-onCreate"
      });
      installed++;
    }
  }

  var clazz = modspec.findClassOrNull(ACTIVITY);
  if (clazz == null) {
    clazz = modspec.waitForClass(ACTIVITY, 15000);
  }
  if (clazz == null) {
    modspec.emit("hook_error", { reason: "UnityPlayerActivity not resolved", target: "com.ChillyRoom.DungeonShooter" });
    return;
  }
  install(clazz, "UnityPlayerActivity");
  if (installed === 0) {
    modspec.emit("hook_error", { reason: "no lifecycle methods resolvable" });
    return;
  }
  modspec.emit("assist_ready", { hooks: installed, target: "com.ChillyRoom.DungeonShooter" });
}

main();
