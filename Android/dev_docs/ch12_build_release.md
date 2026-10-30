# 第 12 章 构建与发布

> 材料分层：A = 官方构建文档（developer.android.com/build），B = 通行工程知识。版本事实（2026-10 校准）：**AGP 9.0**（要求 Gradle 9.1，内置 Kotlin 支持，新增 Android KMP 库插件）。↔ 通用题：构建/工程化相关。

## 12.1 Gradle 生命周期与性能

三阶段：**Initialization**（settings.gradle 确定参与项目）→ **Configuration**（执行所有 build.gradle 脚本，生成任务图）→ **Execution**（按依赖图跑任务）。性能要点：

- 大部分"改一行等半天"都死在 Configuration——脚本里**避免顶层执行重逻辑**（读文件/网络），只声明 Task；
- `org.gradle.caching=true` + `parallel=true` + Configuration Cache（AGP 8+ 成熟）；
- KSP 替代 kapt（注解处理提速数倍，Room/Hilt 均已支持）。

## 12.2 版本对应与升级

AGP ↔ Gradle ↔ SDK 有严格对应表（官方 [AGP 发布说明](https://developer.android.com/build/releases/gradle-plugin)），升级顺序：先 Gradle Wrapper → AGP → Kotlin → 依赖。版本号与 Android Studio 绑定（IDE 版本内嵌对应 AGP 最低要求）。

## 12.3 工程组织（现代写法）

- **Version Catalog**（libs.versions.toml）统一依赖版本；共享逻辑用 **Convention Plugin**（build-logic 复合构建）替代 buildSrc（buildSrc 改动全量重编）；
- 多模块分层（B 层通行结构）：`app`（壳）+ `core:*`（model/design/network/db）+ `feature:*`（业务，依赖 core）——模块化收益：并行编译、依赖边界清晰、发布粒度；
- Groovy → Kotlin DSL（.kts）是官方默认。

## 12.4 构建变体

| 维度 | 内容 | 典型 |
|---|---|---|
| buildType | debug/release | minifyEnabled/signingConfig/debuggable |
| productFlavor | 业务形态 | free/paid、国内/海外 |
| dimension | flavor 分组 | channel × market |
| sourceSet | 覆盖优先级 | flavor > buildType > main |

`BuildConfig` 字段按变体注入（API host/开关）；渠道包生产环境用 Play 分发或 VAS 方案（C 层：国内多渠道现状）。

## 12.5 R8 混淆与瘦身

三种作用：**shrink**（删未引用代码）/ **optimize**（内联等优化）/ **obfuscate**（改名 a.b.c）。规则要点：

- `-keep class X` 保留类不被删/改名；`-keepclassmembers` 只保成员；反射/序列化/JNI/`@Serializable` 相关类常需补规则；
- `@Keep` 注解 = keep 全量（粗粒度，能用精确规则就不用它）；
- **mapping.txt 必须归档上传**（崩溃堆栈反混淆的唯一依据）；`retrace` 工具还原；
- 体积优化：`resConfigs "zh", "en"` 限语言、webp 图片、So 按 abi split。

## 12.6 签名

| 方案 | 覆盖 | 说明 |
|---|---|---|
| v1（JAR） | 逐条目 | 老，Android 11+ 可不签 |
| v2 | 整个 APK 字节 | Android 7+ 必备 |
| v3 | 密钥轮换支持 | 11+ 推荐 |
| v4 | 增量安装（ADB） | 12+ 可选 |

**Play App Signing**（A 层）：上传密钥（本地保管）→ Google 用应用签名密钥出最终包——密钥丢失可重置上传钥；本地 keystore 务必离线备份。

## 12.7 AAB 与 Play 分发

- **AAB（App Bundle）**：上传单一制品，Play 按设备 split 出 APK（ABI/语言/密度），用户下载显著变小；
- 发布轨道：internal → closed → open → production；**分阶段发布（staged rollout）**按比例放量 + Pre-launch report 自动云测；
- 国内渠道仍是多 APK/多包（差异包 + 渠道号），策略与 Play 不同（C 层）。

## 12.8 Kotlin Multiplatform（KMP）概览

- 共享业务逻辑层（data/domain），UI 可选 Compose Multiplatform；`expect/actual` 声明平台差异实现；
- AGP 9.0 新增 Android KMP 库插件，官方工具链已把 KMP 纳入主航道（2026 校准事实）；Android 侧引入门槛：依赖需 KMP 兼容（klib/源集）。

## 12.9 发布 Checklist

versionCode 递增 ✓ targetSdk 达标 ✓ 签名配置与 mapping 归档 ✓ ProGuard 回归（反射路径）✓ 灰度 5% 观察 Vitals ✓ 全量。

↔ 通用题：构建/工程化相关。
