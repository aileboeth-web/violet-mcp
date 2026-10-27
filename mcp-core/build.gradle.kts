plugins {
    id("org.jetbrains.kotlin.jvm")
}

// 纯 JVM 模块：MCP 协议、HTTP 传输、鉴权、只读白名单、中继客户端。
// 不依赖 Android，App 和电脑上的测试/互操作 harness 共用同一份代码。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Android 平台自带 org.json，这里只参与编译；JVM 测试时使用真实实现
    compileOnly("org.json:json:20240303")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// 互操作测试用的 JVM harness：用与 App 相同的协议/传输/中继代码启动一个带模拟只读工具的服务器。
// 用法：gradlew :mcp-core:runHarness --args="--port 18080 --token xxx [--relay wss://... --device-secret ... --pin sha256/...]"
tasks.register<JavaExec>("runHarness") {
    group = "verification"
    description = "Run the JVM interop harness (fake read-only tools, real MCP transport)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.mcp.server.core.harness.HarnessMainKt")
}
