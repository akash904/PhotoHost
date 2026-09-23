# R8 rules for the release build.
#
# Until now this file held a note saying the Ktor, serialization and Room keeps would arrive with
# M2 and M3. They did not, while isMinifyEnabled stayed true, so no release build has ever been
# produced -- let alone run. Everything below is either required to make R8 finish, or required to
# stop it producing a build that compiles and then fails only once installed.

# ---------------------------------------------------------------- Ktor

# Ktor asks the JVM management API whether an IntelliJ debugger is attached. Those classes do not
# exist on Android and the call is never reached there, so R8 only needs to be told not to treat
# the dangling reference as an error. This is the one that currently fails the build outright.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ---------------------------------------------------------------- kotlinx.serialization

# The compiler plugin emits a $$serializer class for every @Serializable type and reaches it
# through the type's Companion. Neither is called from anywhere R8 can see, so without these both
# get discarded and every API response fails to parse -- in release only.
-keepattributes *Annotation*, InnerClasses

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------- Room

# Room resolves the generated implementation of a @Database by building its name at runtime and
# calling Class.forName, which is invisible to R8. Losing it means the index cannot be opened.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ---------------------------------------------------------------- enums stored as text
#
# These two are the subtle ones. Both are persisted by NAME -- DeviceRole into preferences and read
# back with valueOf, StoreKind into preferences and into the volumes.kind column -- and R8 is free
# to rename enum constants. It would stay self-consistent within a single build, so nothing would
# look wrong in testing; the damage appears across an update, when a value written by yesterday's
# mapping no longer parses under today's. DeviceRole failing to parse silently resets a phone to
# "not set up yet", and StoreKind failing to match sends a library configured for a USB drive back
# to internal storage.
-keep enum dev.gpicalter.core.DeviceRole { *; }
-keep enum dev.gpicalter.storage.StoreKind { *; }

# The generic form, for any enum reached through values() or valueOf().
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
