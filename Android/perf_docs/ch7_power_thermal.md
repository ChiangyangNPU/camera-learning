# 第 7 章 功耗与热

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 相机是整机最重的负载之一。功耗与热决定"长时间使用的体验下限"：冷机首帧再快，录像 5 分钟就降频掉帧也是失败。本章讲热源构成、官方 Thermal API 的接入代码、降级矩阵与滞回设计、功耗测量工具链与热稳态基线。

## 7.1 热源与功耗构成

### 7.1.1 热源清单

| 热源 | 机制 | 与什么线性相关 |
|---|---|---|
| Sensor + ISP | 每帧光电转换与管线处理 | 分辨率 × 帧率 |
| 多路流带宽 | DDR 读写（预览+分析+录像并存最重） | Σ(流带宽)，见 6.4 |
| GPU/NPU | 滤镜、美颜、ML 推理（连续负载，无空闲帧） | pass 数 × 分辨率；推理频率 |
| 编码器 | 录像硬编持续大吞吐 | 码率 × 帧率 |
| 屏幕 | 相机场景常亮 + 高亮 | 亮度（系统项，App 可建议策略） |

### 7.1.2 功耗特性（B 层经验）

- 预览 ≈ 基础负载（sensor+ISP+显示）；**录像 = 预览 + 编码 + 音频，通常再高 30~60%**；
- **非线性叠加**：高帧率 + 高分辨率 + 滤镜同开时，电压/频率爬升使每 MHz 的功耗上升（DVFS 曲线），"各开一项没事、全开就烫"是常态；
- 功耗优化的本质是**按需降规格**：每一帧都用满足体验的最小规格处理——这与第 4 章的降级阶梯、第 6 章的带宽控制是同一件事的三面。

## 7.2 官方 Thermal API：接入代码与档位语义

A 层（[热管理文档](https://developer.android.com/develop/background-work/background-tasks/thermal/thermal-api)）：

### 7.2.1 热状态监听（API 29+）

```kotlin
val pm = getSystemService(PowerManager::class.java)
pm.addThermalStatusListener(mainExecutor) { status ->
    when (status) {
        PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> degrade(DegradeLevel.NONE)
        PowerManager.THERMAL_STATUS_MODERATE -> degrade(DegradeLevel.LIGHT)   // 降帧/减 pass
        PowerManager.THERMAL_STATUS_SEVERE   -> degrade(DegradeLevel.MEDIUM)  // 关滤镜/降分辨率
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY-> degrade(DegradeLevel.HEAVY)   // 限录像/强提示
    }
}
```

档位语义（A 层定义，动作是相机侧建议）：`NONE → LIGHT → MODERATE → SEVERE → CRITICAL → EMERGENCY → SHUTDOWN`，由系统按整机状态（多传感器融合）判定。

### 7.2.2 预测式降级：getThermalHeadroom（API 30+）

```kotlin
// 录像中每 30s 轮询：预测 30s 后的热余量
val headroom = pm.getThermalHeadroom(30)
if (headroom > 0.85f) degrade(DegradeLevel.LIGHT)   // 还没到 MODERATE 就提前动作
```

`headroom` 返回预测的热余量（0~1+，越小越热）。**预测式降级**的价值：等 `SEVERE` 回调再动作，降频已经发生（掉帧已被用户看见）；headroom 让你在到达之前降码率——"主动降一点"远好于"被动掉帧"。相机长录场景用**状态回调 + headroom 双信号**。

### 7.2.3 一个重要认知

档位由系统按**整机**判定——相机不一定是最热源（快充 + 环境高温 + 后台大任务都可能）。所以降级了相机还热，要反馈系统侧（充电策略/后台限制），而不是无脑继续压相机画质。

## 7.3 降级矩阵与滞回设计

### 7.3.1 矩阵（示例，接 7.2 的监听）

| 信号（示例阈值） | 动作（叠加执行） |
|---|---|
| headroom > 0.85 或 LIGHT | ML 降频（每 3→5 帧）、滤镜减 pass |
| MODERATE | 预览降帧率（60→30）、关实时滤镜 |
| SEVERE | 降预览分辨率、禁高帧率档、录像降码率 |
| CRITICAL+ | 强提示用户、限制录像时长、引导停止使用 |

### 7.3.2 滞回（防横跳）

温度在阈值附近波动时，单阈值策略会反复降级/恢复——画质"闪烁"比稳定的低画质更糟。设计：

```
降级阈值：headroom < 0.85 → 降到 LIGHT
恢复阈值：headroom > 0.95 且持续 60s → 升回（阈值更宽松 + 时间确认）
```

即**降级容易、恢复谨慎**（升回条件 = 降级条件 + 迟滞区间 + 持续时间）。恢复还要逐级（HEAVY→MEDIUM→LIGHT），不一步跳回全量。

### 7.3.3 降级事件入指标

降级触发率（线上会话占比）与触发时长分布必须上报（第 8 章）——它是功耗回归的**先导指标**：某版本降级触发率翻倍，用户还没抱怨发烫，数据已经报警。

## 7.4 功耗测量工具链

| 工具 | 层级 | 典型用法 |
|---|---|---|
| `dumpsys batterystats` + [Battery Historian](https://developer.android.com/topic/performance/power/setup-battery-historian) | 软件 | 标准流程：`adb shell dumpsys batterystats --reset` → 跑固定脚本 → `dumpsys batterystats > bs.txt` → historian 可视化；看耗电归因（wake lock/传感器/CPU 频率/屏幕）与相机使用时段；适合版本对比 |
| Android Studio Energy Profiler | 开发期 | CPU/网络/定位耗电事件可视化，快速验证"谁在耗电" |
| Perfetto `power.rails` 数据源 | 硬件级 | **部分设备**（如 Pixel）可采实际电流轨（A 层 [Perfetto 文档](https://perfetto.dev/docs/)），与 camera track 对齐做"哪段管线耗电"归因 |
| SoC 厂商工具（如 Snapdragon Profiler） | 硬件级 | CPU/GPU/DDR 频率-功耗联动分析，平台调优常用 |
| Monsoon 等外接功耗仪 | 物理级 | 直接测整机电流，实验室金标准；发布前基准标定 |

**工具选择的工程建议**（B 层）：日常回归用 batterystats（可脚本化进 CI）；深度归因用 power rails/厂商工具；发布前用外接功耗仪标定一次绝对值（软件侧的 mAh 估算是模型值，会漂）。

## 7.5 功耗优化清单（每条带原理）

| 手段 | 原理 |
|---|---|
| 流规格最小化 | 热源与分辨率×帧率线性（7.1.1）——扫码档用 640@15 而不是 1080p@30 |
| 预览 PRIV 直通 | 免掉 App 侧 YUV 的 DDR 往返（6.4.2） |
| 不用的流移除 | 配着的流就耗带宽与 ISP |
| 滤镜 pass 精简 / 处理帧率 < 显示帧率 | GPU 降负载；24fps 处理在 30fps 显示下几乎无感 |
| ML 降频复用 | NPU 是连续大负载，每 3~5 帧一次 + 结果插值 |
| 接近传感器灭屏（贴脸） | 屏幕是常亮大头 |
| 场景化帧率档 | 扫码 15fps、预览 30、取景高帧率仅用户持稳时开 |
| 后台彻底释放相机 | 后台持有 = 白耗电 + 会被抢占（还占着别家的资源） |
| WakeLock 用完即还 / 任务走 WorkManager | Vitals 监控滥用（A 层 [功耗指南](https://developer.android.com/topic/performance/power)） |

**度量纪律**：30min 标准脚本（预览/录像各一轮，固定亮度/信号/温机）对比，杜绝"体感优化"——功耗的体感误差可达 30%。

## 7.6 热稳态基线：性能预算必须包含的口径

冷机数据漂亮、热稳态崩掉的版本，真实体验就是差。所以预算（第 1 章）里"热稳态口径"单独成行：

- **实验室口径**：连续录像 20min，记录帧率/出图延迟/码率随时间的衰减曲线，标记降级触发点（如"第 8 分钟开始降码率"）；
- **对比口径**：版本间比"降级触发时间点"（越晚越好）与降级后的稳定值；
- **线上口径**：降级触发率、触发时长分布（8.2）。

一条 B 层经验：热稳态劣化往往来自"看起来无关"的改动（某版本把日志加密上移了 2ms/帧、某滤镜多了一个 pass）——**只有常跑的长时用例能抓到**，短用例永远绿灯。

---

> 来源与延伸：[Thermal API](https://developer.android.com/develop/background-work/background-tasks/thermal/thermal-api)、[功耗优化](https://developer.android.com/topic/performance/power)、[Battery Historian](https://developer.android.com/topic/performance/power/setup-battery-historian)、[Perfetto](https://perfetto.dev/docs/)；降级矩阵/滞回/热稳态基线为通行实践（B 层）。录像的编码器与前台服务纪律见第 5 章；降级动作与第 4 章 GL 降级阶梯是同一套机制。
