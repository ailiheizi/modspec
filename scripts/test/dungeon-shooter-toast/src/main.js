// ModSpec hook demo — visible proof of the hook inside the game.
//
// DungeonShooter is a Unity game; the Java side is a thin shell around the
// C# core, so the hookable surface is the SDK bridge and Unity player
// classes. Each fired hook shows an Android Toast in-game (requires the
// `toast` capability) plus a structured `hook_hit` event.
//
// Observe-only: no arguments or results are modified.

var APP = "com.chillyroomsdk.sdkbridge.BasePlayerApplication";
var ACTIVITY = "com.chillyroom.unityextend.UnityPlayerActivity";
var HOOKED = {};

function install(clazzName, methodName, params, label) {
  var clazz = modspec.findClassOrNull(clazzName);
  if (clazz == null) {
    modspec.log("class not found:", clazzName);
    return;
  }
  var method = modspec.findMethodOrNull(clazz, methodName, params);
  if (method == null) {
    modspec.log("method not found:", clazzName + "." + methodName);
    return;
  }
  modspec.hook({
    clazz: clazz,
    method: methodName,
    params: params,
    before: function(ctx) {
      // ctx.toast uses the hook receiver (Application/Activity) as the
      // Toast context — reliable inside callbacks.
      ctx.toast("ModSpec hook: " + label);
      modspec.emit("hook_hit", { hook: label, method: clazzName + "." + methodName });
    },
    id: "demo-" + label
  });
  HOOKED[label] = true;
  modspec.log("hook installed:", label);
}

function main() {
  // Application.onCreate: fires when the game process starts. The class is
  // loaded at process start; wait briefly only when it is not yet present
  // (within the manifest execution budget of 20s).
  var appClass = modspec.findClassOrNull(APP);
  if (appClass == null) {
    appClass = modspec.waitForClass(APP, 15000);
  }
  if (appClass != null) {
    install(APP, "onCreate", [], "app.onCreate");
  }

  var act = modspec.findClassOrNull(ACTIVITY);
  if (act == null) {
    act = modspec.waitForClass(ACTIVITY, 15000);
  }
  if (act != null) {
    install(ACTIVITY, "onResume", [], "activity.onResume");
    install(ACTIVITY, "onPause", [], "activity.onPause");
  }

  var count = 0;
  for (var k in HOOKED) count++;
  if (count === 0) {
    modspec.emit("hook_error", { reason: "no hookable classes resolved", target: "com.ChillyRoom.DungeonShooter" });
    return;
  }
  modspec.emit("hook_demo_ready", { hooks: count, target: "com.ChillyRoom.DungeonShooter" });
  modspec.toast("ModSpec hook demo active (" + count + " hooks)");
}

main();
