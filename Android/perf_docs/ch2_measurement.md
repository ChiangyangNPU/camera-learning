# 第 2 章 度量方法学：实验室、线上与程序化监控

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 第 1 章定义了"测什么"，本章解决"怎么测"：三种度量环境的分工与闭环、埋点设计、程序化帧监控（FrameMetrics/JankStats）的原理与代码、统计口径的公平性、以及预览端到端延迟的测量。trace 工具的抓取配置与判读见《Android_Camera_Trace分析文档》第 2~3 章，本章不重复。

## 2.1 三种度量环境的分工与闭环

| 环境 | 手段 | 优势 | 局限 |
|---|---|---|---|
| 实验室 | 固定脚本 + Perfetto/dumpsys + 外设（功耗仪/高速摄像/标准灯箱） | 可复现、可归因（有完整 trace） | 环境理想化，掩盖真实长尾（用户的后台负载/温度/存储状态无法模拟） |
| 线上 | 分段埋点 + 计数器 + 异常采样上报 | 真实分布、机型覆盖广 | 难归因——只能告诉你"哪段慢"，不能告诉你"为什么慢" |
| App 内程序化 | FrameMetrics/JankStats/Choreographer/自定义打点 | 持续运行、粒度到帧、无需外部设备 | 只覆盖 App 侧视角（系统侧/HAL 侧看不到） |

三者不是三选一，是一条**证据链闭环**：

```
线上发现（p95 劣化告警）
  → 实验室复现（同机型同脚本 + Perfetto 采集）
    → trace 归因（定位到 App/框架/HAL/资源，见《Trace分析文档》第 5 章）
      → 修复 → CI 回归（防退化）→ 线上验收（下个版本曲线回落）
```

只有实验室没有线上：优化偏离真实用户（实验室永远 25℃ 满电飞行模式）；只有线上没有实验室：问题发现了但下钻不了。两个都有的团队，才是"性能可运营"。

## 2.2 埋点设计（线上）

### 2.2.1 分段埋点与 trace 同名

原则：**埋点名与 Perfetto 打点名统一**（如 `Camera#sessionConfig`），这样线上数据与实验室 trace 能互相印证——线上说"配流段 p95 劣化"，实验室抓 trace 直接搜同名切片。

```kotlin
// 同一段工作，双通道记录：trace（实验室归因用）+ 埋点（线上统计用）
fun openCameraWithMetrics() {
    val t0 = elapsedRealtime()
    Trace.beginAsyncSection("Camera#open", 0)
    cameraManager.openCamera(id, object : StateCallback() {
        override fun onOpened(device: CameraDevice) {
            Trace.endAsyncSection("Camera#open", 0)
            PerfTracker.report("Camera#open", elapsedRealtime() - t0, dims)
            ...
        }
        ...
    }, handler)
}
```

### 2.2.2 环境维度必带

一条耗时数据不带维度就没有分析价值。最低维度集：

`机型 | SoC | 系统版本 | 前后摄 | 冷/温/热启动 | 光照档（可用 AE 状态/曝光时间近似分桶）| 温度档（Thermal status）| 电量/充电态 | 存储剩余`

维度的作用举例：出图延迟整体正常，但"入门机 × 慢速存储 × 电量低于20%"桶里 p95 是 3s——没有维度这条永远查不出来。

### 2.2.3 采样与上报策略

- **计数器全量**：帧数、掉帧数、拍照失败数、抢占数、降级触发数（这些是低频小数据）；
- **分段耗时采样**：按用户比例（如 10%）上报明细，量级可控；
- **异常样本留详情**：分段耗时超阈值时，附加采集"该次会话的完整分段 + 相机状态快照（dumpsys 摘要）"，供回捞归因；
- **上报节奏**：会话结束批量上报，不要逐帧打网络请求。

### 2.2.4 告警规则

版本对比告警（新版本 p95 - 上版本 p95 > 阈值即报警），不要用绝对值告警（绝对值告警会因机型分布波动一直响，很快被忽略）。告警带维度下钻链接：哪一档、哪个场景劣化，一键可看。

## 2.3 程序化帧监控：FrameMetrics 与 JankStats

### 2.3.1 Window.OnFrameMetricsAvailableListener（API 24+）

系统在每帧渲染完成后回调（A 层，[FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)），提供该帧各阶段耗时：

```java
window.addOnFrameMetricsAvailableListener((win, frameMetrics, dropCount) -> {
    float totalMs  = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000f;
    float layoutMs = frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) / 1_000_000f;
    float drawMs   = frameMetrics.getMetric(FrameMetrics.DRAW_DURATION) / 1_000_000f;
    float swapMs   = frameMetrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION) / 1_000_000f;
    FrameTracker.record(totalMs, layoutMs, drawMs, swapMs);
}, handler);
```

各字段的含义（对应渲染管线，A 层字段定义 + B 层解读）：

| 字段 | 覆盖阶段 | 超标常见原因 |
|---|---|---|
| `LAYOUT_MEASURE_DURATION` | measure/layout 遍历 | 布局过深、requestLayout 风暴、列表重绑 |
| `DRAW_DURATION` | View 绘制指令录制（DisplayList） | onDraw 复杂、大量 invalidate 区域 |
| `SWAP_BUFFERS_DURATION` | 缓冲提交/交换 | GPU 排队、合成背压 |
| `TOTAL_DURATION` | 从 VSync 到呈现的整帧 | 兜底口径（与 Vitals 的 16ms/700ms 对齐） |

注意：回调本身有开销，线上建议按窗口启用（如疑似卡顿的页面）而不是全程常开；`dropCount` 表示系统丢弃了多少帧回调（说明回调处理太慢）。

### 2.3.2 androidx JankStats

在 FrameMetrics（API 24+）/Choreographer（低版本回退）之上封装（A 层，[官方文档](https://developer.android.com/topic/performance/vitals/jank-stats)），输出每帧 `FrameData(frameStartNanos, durationMs, isJank)`：

```kotlin
val jankStats = JankStats.createAndTrack(window) { frameData ->
    if (frameData.isJank) JankTracker.report(frameData.durationMs)
}
jankStats.jankHeuristicMultiplier = 2f   // jank 阈值 = 刷新周期 × 2（可调）
```

它的官方定位就是**生产环境监控**：低开销、可配置阈值、带 `OnFrameListener` 挂钩。适合接入第 1 章的 Jank 率指标做线上采集。

### 2.3.3 相机 App 的关键区分（最容易搞错的一点）

**JankStats/FrameMetrics 监控的是 View 渲染帧**。相机预览走 SurfaceView 的独立合成层（HAL→BufferQueue→SF，不经过 View 的 measure/layout/draw，见第 4 章 4.3），所以：

- JankStats 上报的帧数据反映的是**相机 App 的 UI**（设置面板、滤镜选择页、相册缩略墙），不是预览画面；
- 预览出帧要用相机侧口径：`onCaptureStarted` 间隔统计（出帧节奏）+ `SurfaceTexture` 时间戳（管线延迟，见 2.6）；
- 两个数据源**分开发报**，看板上分开展示——混在一个"帧率"里，预览掉帧会被 UI 帧率稀释到看不见。

其他程序化手段：`Choreographer.postFrameCallback` 的 `frameTimeNanos` 差值（自测帧间隔）；诊断期的 `StrictMode`（主线程 IO/网络违规即时暴露，A 层）。

## 2.4 统计口径与公平性

| 规则 | 为什么 |
|---|---|
| 分位数（p50/p95/p99）而非均值 | 少量极端样本拉不动分位数但决定长尾体验；掉帧类指标看 p95/p99 |
| 剔除冷启动段（帧率统计去前 1s） | 启动抖动污染稳态数据，稳态与启动是两个指标（第 1 章） |
| 分场景统计（亮/暗、前/后摄、帧率档） | 暗光掉帧是曝光物理约束（第 4 章），与亮光混算会互相稀释 |
| 样本量 N≥30 再下结论 | 小样本的分位数无意义；A/B 对比要有显著性概念 |
| 环境全记录（温度/电量/后台/充电） | 热降频与低电降频会系统性改变结果（第 7 章） |

一个均值误导的例子（B 层教学案例）：100 次启动，95 次 500ms、5 次 4000ms。均值 675ms"看起来还行"，p95 = 4000ms——那 5% 用户遇到的是黑屏 4 秒，差评就从这来。**相机场景的长尾通常来自低端机 + 冷 provider + 慢存储，正是均值最掩盖的人群。**

## 2.5 实验室搭建要点

- **环境控制**：标准光照（亮箱/暗箱，可复现 AE 行为）、温机与冷机分开跑（热状态是系统性变量，第 7 章）、飞行模式、固定后台负载、屏幕亮度锁定；
- **机型矩阵**：按"SoC 代次 × 摄像头配置"选 3~5 台代表机型覆盖主要分叉（比堆台数重要——覆盖度看分叉不看数量）；
- **脚本化**：固定操作序列（冷启动→切模式→亮光拍照→暗光拍照→连拍 5 张→录像 10min→后台 30s 返回→退出），用 uiautomator/Macrobenchmark 驱动，人手操作不可复现；
- **自动化基准**（A 层，[Macrobenchmark](https://developer.android.com/macrobenchmark)）：

```kotlin
@get:Rule val benchmarkRule = MacrobenchmarkRule()

@Test
fun coldStartup() = benchmarkRule.measureRepeated(
    packageName = "com.example.camera",
    metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
    iterations = 10,
    compilationMode = CompilationMode.Partial(),   // 模拟真实编译状态
) {
    pressHome(); device.pressKeyCode(KEYCODE_CAMERA_APP...)  // 或 startActivityAndWait
}
```

`StartupTimingMetric` 给 TTID/TTFD（Activity 口径，第 1 章的差异说明）、`FrameTimingMetric` 给滚动/动画的帧耗时分位。**相机出帧速率没有现成 metric**——由被测 App 内埋点导出，或脚本在测量后读 `dumpsys media.camera` 的帧计数。

- **trace 同步采集**：脚本运行时自动起 Perfetto（配置见《Trace分析文档》2.2），每个用例产出配套 trace——异常样本自带现场，不用等复现；
- **报告**：每机型 p50/p95 对基线版本对比，劣化即标红进 CI 门禁（第 8 章 8.3）。

## 2.6 特殊测量：预览端到端延迟（capture-to-display）

预览延迟 = 场景变化 → 传感器曝光 → 相机管线 → 处理链 → 合成 → 屏幕点亮像素的总时间。扫码对焦、直播、AR 类产品对这个指标敏感（行业术语 motion-to-photon 借自 AR，C 层）。三层测法：

### 2.6.1 时间戳差值法（App 内可做）

原理：`SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME` 的设备上，相机帧的传感器时间戳与 `SystemClock.elapsedRealtimeNanos()` 同基准（A 层），于是：

```
管线延迟 ≈ onFrameAvailable 时刻的 System.nanoTime()
          − SurfaceTexture.getTimestamp()（该帧的传感器时间戳）
```

这测得的是"曝光 → App 侧可采样"；再叠加合成/送显 1~2 个刷新周期近似端到端。注意：`UNKNOWN` 基准的设备上此法无效（时间戳基准未定义）；滤镜链会增加该值（处理 pass 数 × 每 pass 时间 + 队列深度）。

### 2.6.2 高速摄像法（实验室金标准之一）

240fps+ 慢动作相机同时拍"被摄物运动"与"手机屏幕"，逐帧比对物像与屏像的相位差：

```
端到端延迟 = 相位差帧数 ÷ 高速摄像帧率
例：240fps 下滞后 9 帧 → 9/240 ≈ 37.5ms
```

用 LED 闪光触发（灯亮 vs 屏幕出现灯像）同理，测的是纯"光→屏"链路，排除了算法判断的模糊性。

### 2.6.3 FrameTimeline（系统侧权威口径）

Perfetto 的 FrameTimeline 轨道（Android 12+，A 层）给出每帧的期望呈现时刻与实际呈现时刻及 jank 归属——作为线上无法获取时的实验室权威口径，与 2.6.1 的 App 侧数字互为校验。

---

> 来源与延伸：[FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)、[JankStats](https://developer.android.com/topic/performance/vitals/jank-stats)、[Macrobenchmark](https://developer.android.com/macrobenchmark)、[StrictMode](https://developer.android.com/reference/android/os/StrictMode)；高速摄像与时间戳差值为通行实践（B 层）。采集之后的分析工具链与归因方法转《Android_Camera_Trace分析文档》第 3~5 章。
