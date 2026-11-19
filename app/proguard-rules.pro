# kotlinx.serialization models in :core:files
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# Privacy: release builds write nothing to logcat below error level. PDFBox logs warnings that can
# include font and file details from the user's documents; Folio's own code doesn't log.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static boolean isLoggable(java.lang.String, int);
}
