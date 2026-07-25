# Provider splits are released independently from the base APK. Optimization can rewrite calls
# across that boundary and obfuscation can rename them, so both stay disabled for release builds.
-dontoptimize
-dontobfuscate

# Keep the supported shared ABI present in a standalone base build. Provider-only dependencies may
# still be shrunk normally from their feature split.
-keep class io.github.ciurlaro.codexmobile.provider.api.** { *; }
-keep class io.github.ciurlaro.codexmobile.platform.android.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }

# Pdfium registers these classes and their complete JNI method tables by name during JNI_OnLoad.
-keep class io.legere.pdfiumandroid.core.jni.** { *; }
-keep class io.legere.pdfiumandroid.core.util.PdfiumNativeSourceBridge { *; }
-keep class io.legere.pdfiumandroid.api.PdfWriteCallback { *; }
-keep class io.legere.pdfiumandroid.PdfPasswordException { *; }
