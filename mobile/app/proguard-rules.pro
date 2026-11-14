# CameraX traz as próprias regras de consumidor.
# ML Kit (reconhecimento de texto embutido): a biblioteca nativa chama classes Java pelo nome,
# então o R8 não pode renomear nem remover. Sem isso o release dá NullPointerException em process().
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.odml.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
