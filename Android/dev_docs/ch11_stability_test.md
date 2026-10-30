# 第 11 章 稳定性与测试

> 材料分层：A = 官方文档（Vitals/测试指南），B = 通行工程知识，C = 社区经验（测试比例提法）。↔ 通用题：性能板块稳定性部分。

## 11.1 稳定性问题分类

| 类型 | 本质 | 典型根因 |
|---|---|---|
| Java Crash | 未捕获异常 | 空指针/角标/状态竞争 |
| Native Crash | 信号（SIGSEGV 等） | SO 库/内存越界 |
| ANR | 主线程消息循环阻塞 | 同步 binder/锁/IO（↔ 3.8） |
| OOM | 堆耗尽 | 泄漏累积/大对象 |

- Java 崩溃流程：默认 UncaughtExceptionHandler 记录 → 杀进程；自建处理器需先记录再放行链（否则丢失系统收尾）；
- Native 崩溃：tombstone 可从 `/data/tombstones` 或 bugreport 取；三方捕获（xCrash 等，C 层）用信号处理器 + unwind；
- Crash 上报要素：进程/线程/堆栈（**release 需上传 mapping.txt 反混淆**，↔ 12.5）、设备与 OS 版本、内存水位、近期 breadcrumb。

## 11.2 ANR 定位流程

1. 拿 trace：`adb bugreport` → FS/data/anr/anr_*；
2. 看 main 线程栈顶：锁（BLOCKED 持有者）、binder（对端进程是谁）、IO；
3. 交叉看 binder 线程与 CPU 轨（Perfetto 复现线上场景）；
4. 分类治理：同步调用改异步、锁粒度、SP 迁 DataStore（↔ 9.2）。

## 11.3 内存泄漏排查

- LeakCanary 原理（B 层）：watch 销毁生命周期对象 → 弱引用 + ReferenceQueue → 5s 未回收 → dump hprof → 自动算最短引用链；
- 高发榜：静态持有 Activity/View、非静态 Handler（↔ 3.5）、匿名内部类异步任务、注册未注销（广播/监听/EventBus）、单例持 Context（用 applicationContext）、协程用错作用域。

## 11.4 兼容性治理

| SDK 语义 | 含义 |
|---|---|
| compileSdk | 编译用的最高 API（跟最新） |
| targetSdk | 声明适配到的行为版本（**决定官方行为变更是否生效**） |
| minSdk | 最低安装版本（决定可调用下限与安装覆盖率） |

- Google Play 每年要求 targetSdk 达标（滞后一年内的版本，截止日以 Play 政策页为准，A 层）；
- 行为变更三档：**安装即生效**（所有应用）/ **针对 targetSdk 生效** / **需用户开启**——升级 targetSdk 前读官方 Behavior changes 对应页（↔ 第 14 章）；
- 测试策略：低/中/高三档真机矩阵 + 云真机；Camera 类 App 注意厂商 ROM 差异（↔ 本仓库 ISP 文档平台章节）。

## 11.5 测试体系

测试金字塔（比例提法为 C 层经验：多/中/少 ≈ 7/2/1）：

| 层 | 工具 | 要点 |
|---|---|---|
| 单元测试 | JUnit + MockK + kotlinx-coroutines-test（runTest） | ViewModel/Repository 为主；Turbine 测 Flow 发射序列 |
| 组件测试 | Robolectric（JVM 跑 Android 框架） | 依赖库兼容矩阵需对齐 |
| UI 测试 | Espresso（View）/ Compose Test API（语义树） | 同步等待用 IdlingResource / compose 空闲机制 |
| 截图测试 | Paparazzi / Roborazzi（C 层生态） | UI 回归防"像素漂移" |
| 端到端 | Gradle Managed Devices / 云真机 | CI 可复现 |

Room/DataStore/网络层用 fake + TestDispatcher 注入（避免 sleep 式等待）。

## 11.6 质量门禁

- 静态：lint（官方 + 自定义规则）、detekt/ktlint（风格与坏味道）；
- CI 卡口：单元测试覆盖率阈值、启动/帧性能基准回归（Macrobenchmark 与上版基线对比，↔ 性能文档 ch8 CI 门禁节）；
- 线上：Vitals 红线（崩溃率/ANR 率/掉帧率）+ 分版本/机型看板。

↔ 通用题：稳定性相关题。
