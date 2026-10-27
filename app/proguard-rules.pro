# org.json 由 Android 平台提供；OkHttp 自带 consumer rules。
# 本应用不使用反射加载工具，无需额外 keep 规则。
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
