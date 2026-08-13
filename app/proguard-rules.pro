# 项目默认混淆规则（当前 release 未开启 minify，此文件备用）
# keep JavascriptInterface 注解方法
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
