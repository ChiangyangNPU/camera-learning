# 第 8 章 稳定性与线上体系

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 性能优化的最后一公里是"不退化"：稳定性兜底（ANR/崩溃/抢占）、线上监控、CI 门禁、事故复盘与体系成熟度。本章把前七章的散点收进一个可持续运转的闭环。

## 8.1 相机的稳定性场景：根因与修法

### 8.1.1 主线程 ANR：相机路径上的同步等待

反模式（三类高频写法）：

```kotlin
// ❌ 1. 轮询等待相机回调（主线程死等）
while (!cameraReady) { }                       // 冻结主线程 → ANR

// ❌ 2. 同步锁跨回调
synchronized(lock) { openCameraSync() }         // 回调持锁 + 主线程等锁

// ❌ 3. 消费链阻塞主线程依赖
// onImageAvailable 里直接写盘，写满后 UI 等"保存完成"提示
```

正确姿势：相机路径**全异步**（回调即终点，不阻塞调用者）+ 每个等待有**超时兜底**（如 openCamera 10s 未回调按失败处理）+ 失败有 UI 出口。ANR 类型与系统阈值参考：输入事件 5s、前台 Service 20s、前台广播 10s（A 层 [ANR 文档](https://developer.android.com/topic/performance/anrs)）。

### 8.1.2 相机被抢占（onDisconnected）

机制：更高优先级客户端打开（通话、安全扫描、系统相机），CameraService 驱逐你（优先级体系见《源码链路文档》第 2 章）。正确处理：

```kotlin
override fun onDisconnected(camera: CameraDevice) {
    camera.close()
    runOnUiThread { showCameraBusyUi() }          // 明示"被占用"，不是黑屏
    cameraManager.registerAvailabilityCallback(...)  // 可用时自动恢复
}
```

纪律：**退避重试**（availability 回调驱动，不是定时器硬轮询）——自动无限重开可能被系统判为异常行为；线上统计"抢占次数 + 自动恢复成功率"（第 1 章 1.5）。

### 8.1.3 服务端卡死与超时兜底

HAL 假死时系统有 CameraServiceWatchdog 自愈（杀进程重启，见《系统面试题》Q6），但**自愈期间 App 的同步等待会先超时**——所以所有等待必须有超时与 `onError` 出口；`ERROR_CAMERA_DEVICE`(4) 需要重开、`ERROR_CAMERA_SERVICE`(5) 通常需要重启设备（A 层错误码语义），App 按码给用户不同提示。

### 8.1.4 配流失败与后台限制

- 配流失败：预检 API（3.2.5）+ 降档重试（减流/降尺寸），而不是直接失败给用户；
- 后台冻结：Android 11+ cached app freezer 冻结缓存进程（A 层），后台持有相机会先被 CameraService 驱逐——**后台彻底释放**，长录走前台服务（5.6.4）。

### 8.1.5 官方红线：Android Vitals

[Android Vitals](https://developer.android.com/topic/performance/vitals)（A 层）的核心行为：用户感知 ANR 率/崩溃率、Slow frames（>16ms）/Frozen frames（>700ms）、wake lock 滥用、过度唤醒。具体行为阈值以 Play Console 当前公布为准（会调整）。**把 Vitals 当外部不可协商红线叠在自家预算上**（自家红线应严于商店红线）；相机 App 的主线程等回调、预览路径卡顿、录像 wake lock 未还，都会直接推高这些指标。

## 8.2 线上监控体系：数据结构到告警

### 8.2.1 事件 schema（示例）

```json
{
  "event": "camera_first_frame",
  "stages": {"t0_t1": 210, "t1_t2": 180, "t3_t4": 150, "t4_t6": 140},
  "total_ms": 680,
  "dims": {
    "model": "PD2186", "soc": "sd778g", "os": "13",
    "camera": "back", "start_type": "cold",
    "light_bucket": "bright", "thermal": "LIGHT", "battery": 85
  },
  "session": "…"
}
```

设计要点（第 2 章 2.2 的展开）：

- **分段耗时 + 总时长同报**（分段用于归因，总时长用于预算）；
- 维度齐全（无维度的均值没有分析价值）；特殊桶（入门机×慢存储×低电）往往藏着长尾真相；
- 计数器类（掉帧数、失败数、抢占数、**降级触发数**）全量上报——它们是低频小数据但价值极高（降级触发率是功耗回归的先导指标，7.3.3）；
- 埋点名与 trace 同名（`Camera#sessionConfig`），线上与实验室证据链互通。

### 8.2.2 告警规则（示例）

```
规则1：新版本冷启动首帧 p95 - 上版本 > 15% → 报警（带机型分档下钻链接）
规则2：某机型档拍照失败率 > 0.5% → 报警
规则3：降级触发率环比翻倍 → 报警（先于用户抱怨发烫）
规则4：音画同步偏移 p95 > 100ms（录像回放埋点）→ 报警
```

为什么**版本对比**而不是绝对值：绝对值会因机型分布/季节（气温）波动一直误报；版本对比问的是"这次发布改变了什么"，噪声小得多。

## 8.3 CI 与自动化回归

### 8.3.1 用例集（对应第 1 章指标）

| 用例 | 采集 | 门禁指标 |
|---|---|---|
| 冷启动×10 | 埋点 + StartupTimingMetric | 首帧 p95 |
| 亮/暗光拍照×20 | 埋点 | 快门/出图 p95 |
| 连拍 10 张 | 埋点 + 内存采样 | 间隔 p95 + 峰值 |
| 录像 10min | 埋点 + batterystats | 帧率稳定性 + 降级触发点 |
| 进出循环×10 | dumpsys meminfo | 内存回到基线（泄漏检测） |
| 预览 5min | JankStats + 相机埋点 | Jank 率 |

### 8.3.2 Macrobenchmark 骨架（A 层）

```kotlin
@Test
fun cameraColdStartup() = benchmarkRule.measureRepeated(
    packageName = "com.example.camera",
    metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
    iterations = 10,
    startupMode = StartupMode.COLD,
    compilationMode = CompilationMode.Partial(),   // 模拟真实编译状态
) { startActivityAndWait() }
// 相机出帧速率等自有指标由 App 内埋点导出（2.5）
```

### 8.3.3 门禁与基线管理

- **门禁规则**：对基线版本比较 p95 与峰值，劣化超阈值（如 10%）阻塞合入——门禁的价值在于"劣化进不了主线"，而不是"报告好看"；
- **基线管理**：基线随版本**有主地**更新（谁、为什么、何时），否则门禁永远红灯然后被绕过；
- **trace 同步**：脚本运行自动抓 Perfetto + dumpsys，异常样本自带现场（不用等复现）；
- **环境一致性**：固定光照/温机流程/飞行模式；设备老化（电池衰减改变 DVFS 行为）要定期校准或轮换。

## 8.4 性能事故复盘模板（含示例）

每起事故按固定模板归档：

1. **现象**：哪个指标、哪个机型档、什么场景劣化多少（"中端档冷启动首帧 p95 800ms→1.4s，集中在电量<20% 样本"）；
2. **定位**：线上数据 → 实验室复现 → trace/dumpsys 证据链（引用归档文件与时间窗）；
3. **根因**：归到层（App/框架/HAL/系统资源），写清因果——"低电模式下 SoC 限频，配流时间翻倍"而不是"感觉变慢了"；
4. **修复与验收**：改动 + 回归数据（修复前后 p95）+ 上线后线上曲线回落截图；
5. **防复发**：补了哪条 CI 用例/告警规则/预算行。

模板的价值在第 5 条——没有它，同类问题半年后重来一遍（低电档场景后来成了 CI 固定用例，就是这条的产物）。

## 8.5 体系成熟度自检

| 级别 | 特征 | 卡点（常见） |
|---|---|---|
| L0 | 无指标，出问题靠复现与经验 | 起步团队 |
| L1 | 指标定义 + 手工测量，版本间可比 | 口径文档没人维护 |
| L2 | 线上分段埋点 + 看板，能发现劣化 | 只报不修，无责任人 |
| L3 | CI 门禁 + 自动 trace，劣化进不了主线 | 门禁维护成本（基线/环境） |
| L4 | 指标-埋点-trace 同名互通、事故模板闭环、预算随硬件例行更新 | 组织问题而非技术问题 |

多数团队卡在 L2→L3：门禁的建立与维护（基线更新流程、设备一致性）有持续成本。向 L4 的关键是把**指标定义文档、基线记录、复盘模板**当代码管理（进仓库、有 review），而不是 wiki 上的孤儿页面。

---

> 来源与延伸：[Android Vitals](https://developer.android.com/topic/performance/vitals)、[ANR 机制](https://developer.android.com/topic/performance/anrs)、[Macrobenchmark](https://developer.android.com/macrobenchmark)、[应用资源管理](https://developer.android.com/topic/performance/memory)；门禁/复盘/成熟度模型为通行工程实践（B 层）。至此与第 1 章闭环：指标定义 → 度量（第 2 章）→ 分域优化（3~7 章）→ 体系防退化（本章）。
