# GhosttyXR / Orb XR — release R8 rules.
# Prefer narrow keeps. Library packages with their own consumer rules are not
# re-kept here (AndroidX, Firebase, Play Billing, ML Kit, Kotlin, Coroutines).

# --- Crash stack readability ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# --- JNI (libghosttyxr.so) ---
# Zig exports are name-mangled:
#   Java_com_ghosttyxr_app_terminal_GhosttyBridge_*
#   Java_com_ghosttyxr_app_terminal_ssh_NativeSshBridge_*
# Class + native method names must match exactly. Default
# proguard-android-optimize.txt also keeps native methods; these are explicit.
-keepclasseswithmembernames class com.ghosttyxr.app.terminal.GhosttyBridge {
    native <methods>;
}
-keepclasseswithmembernames class com.ghosttyxr.app.terminal.ssh.NativeSshBridge {
    native <methods>;
}
