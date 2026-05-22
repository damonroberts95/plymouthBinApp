# OkHttp / Conscrypt
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep all our broadcast receivers + workers + Glance widgets reachable by Android framework
-keep class com.plymouthbins.app.work.NotificationReceiver { *; }
-keep class com.plymouthbins.app.work.BootReceiver { *; }
-keep class com.plymouthbins.app.work.RefreshWorker { *; }
-keep class com.plymouthbins.app.widget.BinsWidget { *; }
-keep class com.plymouthbins.app.widget.BinsWidgetReceiver { *; }

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# BroadcastReceivers
-keep class * extends android.content.BroadcastReceiver

# Glance app widgets
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver

# WebView JS interface bridge in BinBootstrap
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Kotlin metadata for reflection / serialization
-keepattributes RuntimeVisible*Annotations
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# DataStore preference keys are reflection-accessed via name strings — no specific keep needed,
# but suppress warnings from compose-runtime internals.
-dontwarn kotlinx.coroutines.flow.**
