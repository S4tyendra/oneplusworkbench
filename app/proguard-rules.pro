# Release shrinking for Workbench.
# Our own code is kept whole: Xposed loads the hook by name (assets/xposed_init), and the app reaches
# hidden framework APIs and the COUI gallery classes by reflection. R8 still strips unused library code
# (mostly the extended Material icons), which is where the size was.
-keep class dev.satya.workbench.** { *; }
-keep class in.devh.coui.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Hidden framework classes come from the compileOnly :hidden stubs and exist on the device.
-dontwarn **
