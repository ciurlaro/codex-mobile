# Provider entry points are compared with signed marketplace metadata.
-keepnames class io.github.ciurlaro.codexmobile.platform.android.DocumentsProvider
-keepnames class io.github.ciurlaro.codexmobile.platform.android.TelegramProvider

# TDLib registers these JNI methods by their Java names.
-keep class org.drinkless.tdlib.JsonClient { *; }

# Pdfium registers these classes and their complete JNI method tables by name during JNI_OnLoad.
-keep class io.legere.pdfiumandroid.core.jni.** { *; }
-keep class io.legere.pdfiumandroid.core.util.PdfiumNativeSourceBridge { *; }
-keep class io.legere.pdfiumandroid.api.PdfWriteCallback { *; }
-keep class io.legere.pdfiumandroid.PdfPasswordException { *; }
