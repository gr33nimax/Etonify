# The generated go.Seq initialiser reaches this class from patched bytecode, so no
# reachability analysis can see the reference. Shrinking it away breaks the core.
-keep class go.HydraNativeLoader { *; }

# Everything the core reaches from Go through JNI, by name and signature. gomobile resolves
# these at runtime with no reference anywhere in the bytecode, so reachability analysis sees
# them as dead: shrinking or renaming any of it breaks every call into the core, in every
# process, the way the alpha broke before `go.HydraNativeLoader` was supplied at all.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }

# The platform side of the same boundary: libbox calls back into these implementations through
# the interfaces it generated, and the methods are looked up by name.
-keep class io.hydrabox.platform.android.AndroidVpnPlatform { *; }
-keep class io.hydrabox.platform.android.AndroidLocalResolver { *; }
-keep class io.hydrabox.platform.android.SimpleStringIterator { *; }
-keep class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keep class * implements io.nekohasekai.libbox.CommandClientHandler { *; }
-keep class * implements io.nekohasekai.libbox.CommandServerHandler { *; }
-keep class * implements io.nekohasekai.libbox.LocalDNSTransport { *; }
-keep class * implements io.nekohasekai.libbox.InterfaceUpdateListener { *; }

# The wire format between the two processes is hand-written and stable, but the contract types
# travel as data classes whose names appear in nothing but the encoder. Keeping them keeps a
# release build's journal and diagnostics readable.
-keepclassmembers class io.hydrabox.core.contract.** { *; }

# Serialised documents: subscriptions, backups and the generated configuration.
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }
