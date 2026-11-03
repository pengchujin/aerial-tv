# Accessibility service and activities are referenced from the manifest; AGP keeps them.

# R8 inlines OnBackPressedDispatcher$Api34Impl into the dispatcher's constructor, so the class
# that implements OnBackAnimationCallback (API 34) is resolved on every Android version. ART on
# Android 8.0 fails that class and logs NoClassDefFoundError on each Home (XGIMI Z6X, issue #303).
# Kept, the reference stays in a class that only API 34+ loads.
-keep class androidx.activity.OnBackPressedDispatcher$Api34Impl { *; }
-keep class androidx.activity.OnBackPressedDispatcher$Api33Impl { *; }

# JNI entrypoints are resolved by name in the optional silent TCL backlight adapter.
-keep class app.orchard.tv.system.TclBacklight { native <methods>; }
