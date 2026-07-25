# App entry points come from the manifest; generated Android and serialization rules are supplied upstream.
-keep,allowoptimization class io.github.ciurlaro.codexmobile.provider.api.** { *; }
-keep,allowoptimization public class * implements io.github.ciurlaro.codexmobile.provider.api.CodexMobileProvider {
    public <init>(android.content.Context);
}
