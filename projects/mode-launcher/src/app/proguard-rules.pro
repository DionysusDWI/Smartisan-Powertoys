# Shizuku UserService 由 Shizuku **以反射方式**在 shell 进程里加载 —— 不能被混淆/裁剪
-keep class com.shware.mode.shell.UserService { *; }
-keep interface com.shware.mode.shell.IUserService { *; }
-keep class com.shware.mode.shell.IUserService$Stub { *; }
-keep class com.shware.mode.shell.IUserService$Stub$Proxy { *; }

# Shizuku 自身
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# 无障碍服务由系统按类名实例化
-keep class com.shware.mode.input.MetaKeyService { *; }
