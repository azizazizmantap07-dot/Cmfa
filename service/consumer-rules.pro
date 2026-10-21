# Shizuku 13+: newProcess() is private and only reached via reflection in
# ShizukuShell, so R8 would otherwise treat it as unused and strip/rename it.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }
