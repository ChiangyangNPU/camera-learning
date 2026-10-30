# 第 10 章 性能优化总览（索引章）

> 本章是**索引与概览**：完整指标定义、度量方法、代码骨架与线上体系见《Android_Camera_性能分析文档》（8 章详解）；数据采集与判读见《Android_Camera_Trace分析文档》。↔ 通用题：性能板块。

## 10.1 与仓库性能体系的分工

| 主题 | 本章 | 详解所在 |
|---|---|---|
| 指标定义（TTFF/掉帧率/卡顿） | 不重复 | 性能文档第 1 章 |
| 度量（FrameMetrics/JankStats） | 一句话 | 性能文档第 2 章（含代码） |
| 启动/预览/拍照优化 | 通用清单 | 性能文档第 3~5 章 |
| 内存/带宽/功耗热 | 要点 | 性能文档第 6~7 章 |
| Vitals/CI 门禁/复盘 | 一句话 | 性能文档第 8 章 |
| Trace 采集与判读 | 指路 | Trace 文档全文 |

## 10.2 启动优化清单

- 冷启动路径：进程创建（Zygote fork）→ Application.onCreate → Activity 创建/首帧（↔ 第 2.1 节进程模型）；
- 手段：Application 只做必要初始化（其余进 App Startup 懒加载/后台线程）、**Baseline Profile**（关键路径 AOT，官方数据冷启动约 30% 收益）、避免首帧前同步初始化 SDK；
- 观测：`adb shell am start -W`、`reportFullyDrawn()`（灰区标记）、Macrobenchmark `StartupTimingMetric`（A 层）。

## 10.3 渲染优化清单

- 帧预算 16.67ms（60Hz）；瓶颈判定看 trace 的 RenderThread（主线程慢 → 布局/重组；RT 慢 → 绘制复杂度）；
- 通用手段：降低布局层级（ConstraintLayout/Compose 天然单 pass）、避免过度绘制（开发者选项可视化调试）、RecyclerView 复用（↔ 4.6）、主线程零 IO 零 binder 重活；
- 度量：JankStats（androidx）、Vitals 后台掉帧率红线（↔ 性能文档第 8 章）。

## 10.4 内存要点

- Bitmap 尺寸算例：1080×1920 ARGB_8888 = 1080×1920×4 ≈ **7.9MB**——大图必须按 View 尺寸降采样（inSampleSize）；
- 泄漏排查流程：LeakCanary 自动抓 hprof → 引用链定位 → 修复 → 回归（↔ 第 11.3 节、通用题 Q16）；
- 数据结构：大映射用 ArrayMap/SparseArray 替代 HashMap（条目少时省内存）；
- 多媒体场景特殊性（相机/视频）见性能文档第 6 章（maxImages 纪律、缓冲归还）。

## 10.5 功耗

- Doze/App Standby：闲置时网络/Job 被延后——周期任务一律 WorkManager（系统批处理省电，A 层）；
- 精确闹钟（AlarmManager.setExact）需 `SCHEDULE_EXACT_ALARM` 权限且被审查（↔ 第 13.2 节）；
- 热与相机/游戏的降级策略见性能文档第 7 章（Thermal API 监听 + 滞回降级）。

## 10.6 工具矩阵

| 工具 | 用途 | 详见 |
|---|---|---|
| Perfetto | 全链路 trace（CPU/渲染/binder/内存/功耗轨） | Trace 文档 ch1~ch2 |
| Memory Profiler / LeakCanary | 堆与泄漏 | 本文 11.3 |
| Macrobenchmark | 实验室启动/帧指标回归 | 性能文档 ch2/ch3 |
| Baseline Profiles | AOT 优化 | 性能文档 ch3 |
| Android Vitals | 线上用户感知质量 | 性能文档 ch8 |

↔ 通用题：性能板块全部。
