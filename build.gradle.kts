// 顶层构建脚本：仅声明插件版本，不引入任何业务依赖
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
