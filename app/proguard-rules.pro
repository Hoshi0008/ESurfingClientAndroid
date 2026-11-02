# 只需要保留被系统或反射实例化的组件（其余由 R8 正常裁剪）
-keep class com.esurfing.client.service.AuthService { *; }
-keep class com.esurfing.client.service.BootReceiver { *; }
-keep class com.esurfing.client.service.KeepAliveJobService { *; }
-keep class com.esurfing.client.ui.MainActivity { *; }

# 启动器图标等资源由 aapt 处理，这里不需要额外规则
-dontwarn **
