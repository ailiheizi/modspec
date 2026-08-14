-dontwarn io.github.libxposed.annotation.**

-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# Script engine: Rhino/LuaJ access these reflectively (bean conventions and
# Java method lookup), so R8 must not rename or strip their members.
-keep class com.modspec.agent.JsModspecAdapter { *; }
-keep class com.modspec.agent.ScriptBridgeImpl { *; }
-keep class com.modspec.agent.InvocationView { *; }
-keep class com.modspec.agent.ScriptHost { *; }
-keep class org.luaj.vm2.** { *; }
-keep class org.mozilla.javascript.** { *; }
