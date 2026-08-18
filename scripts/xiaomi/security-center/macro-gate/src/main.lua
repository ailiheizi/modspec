-- ModSpec script — MIUI Security Center macro gate (Lua example).
--
-- Identical semantics to the canonical JS implementation (src/main.js):
-- resolve the macro gate class (legacy O3$b names for 12.3.x, then DexKit by
-- the MacroStatusProvider evidence string for 12.7.x MacroUtil), hook the
-- (Context, String, boolean) -> boolean gate methods h (both generations) and
-- g (12.7.x), and allow the macro feature only for
-- com.ChillyRoom.DungeonShooter. Unrelated ActiveNewModel features and the
-- global gate com.miui.gamebooster.utils.K.D are never touched.

local TARGET_GAME = "com.ChillyRoom.DungeonShooter"
local MACRO_FUNCTION = 10020103
local HOOK_ID = "macro-gate-h"
local MACRO_URI = "content://com.xiaomi.macro.MacroStatusProvider/game_macro_change"
local GATE_PARAMS = { "android.content.Context", "java.lang.String", "boolean" }

local LEGACY_O3B_NAMES = {
  "com.miui.securitycenter.O3$b",
  "O3$b"
}

local installedMethods = {}

local function findLegacyO3b()
  for i = 1, #LEGACY_O3B_NAMES do
    local clazz = modspec.findClassOrNull(LEGACY_O3B_NAMES[i])
    if clazz ~= nil then return clazz end
  end
  return nil
end

local function findMacroUtilByDexKit()
  local ok, clazz = pcall(function()
    return modspec.dexFindClass({ usingStrings = { MACRO_URI }, unique = true })
  end)
  if ok and clazz ~= nil then
    modspec.log("macro class resolved by DexKit evidence string")
    return clazz
  end
  modspec.log("dexFindClass(macro class) failed:", tostring(clazz))
  return nil
end

local function resolveMacroClass()
  local legacy = findLegacyO3b()
  if legacy ~= nil then return { clazz = legacy, kind = "legacy" } end
  local dex = findMacroUtilByDexKit()
  if dex ~= nil then return { clazz = dex, kind = "macro_util" } end
  modspec.waitForClass(LEGACY_O3B_NAMES[1], 5000)
  dex = findMacroUtilByDexKit()
  if dex ~= nil then return { clazz = dex, kind = "macro_util" } end
  return nil
end

local function installGate(clazz, methodName)
  for _, installed in ipairs(installedMethods) do
    if installed == methodName then return end
  end
  local method = modspec.findMethodOrNull(clazz, methodName, GATE_PARAMS)
  if method == nil then return end
  modspec.hook({
    clazz = clazz,
    method = methodName,
    params = GATE_PARAMS,
    replace = function(ctx)
      local game = ctx:arg(2)
      if game == TARGET_GAME then
        ctx.result = true -- macro allowed for the target game only
        return
      end
      ctx:callOriginal() -- every other game keeps Security Center behavior
    end,
    id = HOOK_ID .. "." .. methodName
  })
  table.insert(installedMethods, methodName)
end

local function main()
  local resolved = resolveMacroClass()
  if resolved == nil then
    modspec.emit("hook_error", {
      reason = "macro gate class never became available",
      target = TARGET_GAME,
      function_id = MACRO_FUNCTION
    })
    return
  end
  if resolved.kind == "macro_util" then
    installGate(resolved.clazz, "g")
    installGate(resolved.clazz, "h")
  else
    installGate(resolved.clazz, "h")
  end
  if #installedMethods == 0 then
    modspec.emit("hook_error", {
      reason = "macro gate method h/g(Context,String,boolean) not resolvable",
      clazz = tostring(resolved.clazz),
      target = TARGET_GAME,
      function_id = MACRO_FUNCTION
    })
    return
  end
  modspec.emit("macro_allowed", {
    game = TARGET_GAME,
    function_id = MACRO_FUNCTION,
    method = resolved.kind .. ":" .. table.concat(installedMethods, "+"),
    engine = "lua"
  })
  modspec.log("macro gate installed for", TARGET_GAME, "via", resolved.kind)
end

main()
