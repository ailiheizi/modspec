// ModSpec script — MIUI Security Center macro gate (canonical, enabled).
//
// Goal: the game-booster macro feature (ActiveNewModel.functionId=10020103)
// must be allowed ONLY for com.ChillyRoom.DungeonShooter. Security Center
// evaluates macro availability through an obfuscated path; verified evidence:
//
//   12.3.x:  O3.b.h(android.content.Context, java.lang.String, boolean) -> boolean
//            O3.b.g                                (identity anchor field)
//   12.7.x:  MacroUtil (obfuscated as R3.b, unstable name) exposes
//            g/h(Context, String, boolean) -> boolean  — same signature, moved
//            package; identified by the evidence string
//            `content://com.xiaomi.macro.MacroStatusProvider/game_macro_change`
//            (MacroUtil.closeMacro() inserts into that URI). The black-list
//            gate lives in o0.m(game) (`pref_gb_unsupport_macro_apps`) and is
//            called from g(); replacing g() for the target game bypasses it.
//
// Strategy (robust + deterministic):
//   1. Resolve the macro class: legacy static names (12.3.x), then DexKit by
//      the MacroStatusProvider evidence string (12.7.x), then a short
//      waitForClass + DexKit retry for lazy loading.
//   2. Hook the gate methods: `h` (both generations) and `g` (12.7.x only;
//      the 2-arg f() and 3-arg h() are thin wrappers over g(), so covering
//      g()+h() reaches every caller: function list, ActiveNewModel
//      isSupportFunction, DockWindowManagerService, GameBoosterService).
//   3. Replace: true for the target game only, original behavior for every
//      other game (never a global allow/deny).
//   4. Emit structured macro_allowed / hook_error events.

var TARGET_GAME = "com.ChillyRoom.DungeonShooter";
var MACRO_FUNCTION = 10020103;
var HOOK_ID = "macro-gate-h";
var MACRO_URI = "content://com.xiaomi.macro.MacroStatusProvider/game_macro_change";
var GATE_PARAMS = ["android.content.Context", "java.lang.String", "boolean"];

var LEGACY_O3B_NAMES = [
  "com.miui.securitycenter.O3$b",
  "O3$b"
];

var installedMethods = [];

function findLegacyO3b() {
  for (var i = 0; i < LEGACY_O3B_NAMES.length; i++) {
    var clazz = modspec.findClassOrNull(LEGACY_O3B_NAMES[i]);
    if (clazz != null) return clazz;
  }
  return null;
}

function findMacroUtilByDexKit() {
  try {
    var clazz = modspec.dexFindClass({
      usingStrings: [MACRO_URI],
      unique: true
    });
    if (clazz != null) {
      modspec.log("macro class resolved by DexKit evidence string");
    }
    return clazz;
  } catch (e) {
    modspec.log("dexFindClass(macro class) failed:", String(e));
    return null;
  }
}

function resolveMacroClass() {
  var legacy = findLegacyO3b();
  if (legacy != null) return { clazz: legacy, kind: "legacy" };
  var dex = findMacroUtilByDexKit();
  if (dex != null) return { clazz: dex, kind: "macro_util" };
  // The class loads lazily after the game starts; wait briefly, then retry.
  modspec.waitForClass(LEGACY_O3B_NAMES[0], 5000);
  dex = findMacroUtilByDexKit();
  if (dex != null) return { clazz: dex, kind: "macro_util" };
  return null;
}

function installGate(clazz, methodName) {
  if (installedMethods.indexOf(methodName) >= 0) return;
  var method = modspec.findMethodOrNull(clazz, methodName, GATE_PARAMS);
  if (method == null) return;
  modspec.hook({
    clazz: clazz,
    method: methodName,
    params: GATE_PARAMS,
    replace: function(ctx) {
      var game = ctx.arg(1);
      if (game === TARGET_GAME) {
        ctx.result = true; // macro allowed for the target game only
        return;
      }
      ctx.callOriginal(); // every other game keeps Security Center behavior
    },
    id: HOOK_ID + "." + methodName
  });
  installedMethods.push(methodName);
}

function main() {
  var resolved = resolveMacroClass();
  if (resolved == null) {
    modspec.emit("hook_error", {
      reason: "macro gate class never became available",
      target: TARGET_GAME,
      function_id: MACRO_FUNCTION
    });
    return;
  }
  if (resolved.kind === "macro_util") {
    // 12.7.x: f()/h() are wrappers over g(); cover g() + h().
    installGate(resolved.clazz, "g");
    installGate(resolved.clazz, "h");
  } else {
    // 12.3.x: only O3.b.h exists.
    installGate(resolved.clazz, "h");
  }
  if (installedMethods.length === 0) {
    modspec.emit("hook_error", {
      reason: "macro gate method h/g(Context,String,boolean) not resolvable",
      clazz: String(resolved.clazz),
      target: TARGET_GAME,
      function_id: MACRO_FUNCTION
    });
    return;
  }
  modspec.emit("macro_allowed", {
    game: TARGET_GAME,
    function_id: MACRO_FUNCTION,
    method: resolved.kind + ":" + installedMethods.join("+"),
    engine: "js"
  });
  modspec.log("macro gate installed for", TARGET_GAME, "via", resolved.kind);
}

main();
