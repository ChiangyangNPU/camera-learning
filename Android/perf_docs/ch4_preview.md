# 第 4 章 预览流畅性与延迟

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 预览是相机 App 的"常驻负载"：帧预算被刷新率锁死，任何超支直接表现为掉帧。本章讲帧预算的数学、掉帧的四类归因、显示路径的机制（含 CameraX 官方的硬件叠层说法）、GL 处理链预算与降级阶梯、高帧率约束与端到端延迟。

## 4.1 帧预算模型：钱从哪里来，花到哪里去

每个刷新周期是一笔固定预算，三方分账：

```
帧间隔（刷新周期） = 相机出帧（曝光+读出+ISP） + 处理链（GL/NPU，可选） + 显示合成（SF/HWC） + 余量
```

| 刷新率 | 帧间隔 | 相机+显示通常占 | 建议算法+渲染预算（B 层经验） |
|---|---|---|---|
| 30fps | 33.3ms | ~15~20ms | ≤8~10ms |
| 60fps | 16.6ms | ~8~10ms | ≤4~6ms |
| 120fps | 8.3ms | ~4~5ms | ≤2~3ms |

为什么建议值比"剩余时间"再小一截：调度抖动（GC、binder、中断）、SF 合成的波峰、GC 暂停都会瞬时超支——**预算要按 p95 花销而不是均值**留余量（与第 1 章的分位数原则一致）。

优化的第一问永远是"**超支在哪一方**"：相机出帧慢（`onCaptureStarted` 间隔变大）、处理链慢（GL 帧耗时）、还是显示合成慢（FrameTimeline 的 SF jank）——三方对齐时间窗再动手，否则在错误的层优化（最常见错误：相机出帧慢却去优化 shader）。

## 4.2 暗光的物理约束：帧时长的数学

```
帧时长 = max(曝光时间 + 读出时间, FPS range 设定的帧间隔)
帧率上限 = 1000 / 曝光时间(ms)
```

算例：暗光下 AE 把曝光拉到 50ms → 帧率上限 20fps（无论设置多高的 FPS range）；曝光 100ms（夜景手持）→ 10fps。**这不是 bug，是光电转换的信噪比要求**——同样亮度下，缩短曝光就必须提增益，增益放大噪声。

`AE_TARGET_FPS_RANGE` 的语义（A 层）与产品含义：

| 设置 | AE 行为 | 适用 |
|---|---|---|
| `[30,30]` | 曝光钳在 33ms 内，不够亮就提增益 | 预览/录像防抖体验（代价：暗光噪点） |
| `[10,30]` | 允许暗光降帧换曝光 | 拍照预览（所见即所得优先） |
| `[15,15]` | 固定低帧率 | 扫码/低功耗档 |

夜景模式"主动降帧 + 多帧合成"正是这个数学的产品化：单帧物理不够，就用时间换（多帧平均，噪降 √N，见《算法集成文档》第 3 章）。

## 4.3 掉帧归因四分类（机制与证据）

| 类别 | 机制 | 证据 | 典型修法 |
|---|---|---|---|
| ① 输出配置 | 预览尺寸过大（带宽/ISP 超载）、JPEG stalling 流混入预览请求、流组合超限 | dumpsys 流配置；配流参数 | 降预览尺寸；拍照流按需建（两段式） |
| ② 帧时长约束 | 暗光长曝光（4.2 的数学）；FPS range 设错 | `onCaptureStarted` 间隔变大但节奏稳定、无空档 | 分场景统计；产品档位决策 |
| ③ 缓冲 | Image 不归还 → buffer 池耗尽；消费线程慢 → 排队 | ImageReader 消费线程状态；`camera_memory` 密集分配 | 消费后立即 close；丢旧帧 |
| ④ 线程/调度 | 回调 Handler 阻塞、主线程卡顿拖累 UI、CPU 降频/抢占 | Choreographer 超时、cpufreq 轨道、Runnable 堆积 | 独立线程；主线程减负；绑核/优先级 |

判读流程（三方对齐）：**相机出帧 vs UI 渲染先分开**（第 1 章 1.4 的两套口径）→ 相机侧看 `onCaptureStarted` 间隔 + dumpsys 丢帧计数 → UI 侧看 JankStats/FrameTimeline → 显示侧看 SF 的 jank 类型（`SurfaceFlinger CPU/GPU Deadline Missed`）→ 时间窗对齐后归到上表某一类。工具操作细节见《Trace分析文档》第 3~4 章。

## 4.4 显示路径：机制与选择

三条路径的本质差异在**合成方式**：

| 路径 | 合成机制 | 延迟/功耗 | 能力 |
|---|---|---|---|
| SurfaceView（默认） | 独立图层，可由 **HWC 硬件叠层**直接叠加送显（绕过 GPU，A 层：[CameraX Preview 文档](https://developer.android.com/media/camera/camerax/preview) 明确这是 PERFORMANCE 模式的收益来源） | 最低（零拷贝、无 GPU 参与） | 变换/动画受限 |
| TextureView | 内容变成 GPU 纹理，走 View 树的正常合成 | 高一级（多一次上传+GPU 采样） | 支持平移/缩放/透明/动画 |
| GL 中转（SurfaceTexture→GL→SurfaceView） | App 自己渲染后交给 SurfaceView | 取决于 pass 数（可控） | 任意滤镜/变换 |

CameraX 的 `PreviewView` 默认 PERFORMANCE 模式（内部优先 SurfaceView + 硬件叠层；需要动画兼容时切 COMPATIBLE——A 层官方文档）。需要 GL 中转的完整实现（EGL 初始化、OES 外部纹理、多 pass、方向矩阵）见《算法集成文档》第 2 章与《追问链面试题》链 2 的代码骨架。

实践要点：`SurfaceTexture.setDefaultBufferSize` 与相机输出尺寸一致（避免显示端二次缩放开销）；SurfaceView 独立图层意味着它**不受 View 渲染帧率统计覆盖**（第 2 章 2.3.3 已强调）。

## 4.5 GL 处理链的预算与降级阶梯

### 4.5.1 预算内的开源手段

- **降采样处理**：磨皮/虚化类算法对分辨率不敏感——1080p 输入降到 1/2 处理再放大，GPU 时间近似降 75%（面积 1/4）；
- **多 pass 用 FBO ping-pong**：pass 间不回读 CPU；每个 pass 只读写一次纹理；
- **丢旧帧**：处理链入口"只保留最新帧"（队列深度=1），处理不过来直接丢——**绝不排队**（排队 = 延迟累积 + 更卡）；
- **ML 降频**：人脸/分割网络每 N 帧推理一次，帧间用光流/关键点插值或直接复用 mask（边界做时间平滑防抖动）；
- **YUV→RGB 免费**：OES 外部纹理采样时 GPU 自动完成（第《算法集成文档》），不要自己 CPU 转。

### 4.5.2 一个 pass 预算的算例（B 层示意）

1080p、单 pass 全屏 fragment shader 在中端 GPU 约 1~2ms；美颜三件套（磨皮 1 + 肤色 0.5 + 锐化 0.5 pass）+ LUT 0.5 ≈ 2.5~4ms；再加人脸推理（NPU 5~10ms，每 5 帧摊薄 ≈1~2ms）——30fps 预算 8~10ms 内可容纳，60fps（4~6ms）就要砍 pass 或降采样。**上线前必须真机实测**（GPU 代际差异大），预算表按机型分档维护。

### 4.5.3 降级阶梯（从轻到重，逐级可回退）

```
降算法分辨率（1/2 → 1/4）
  → 减 pass 数 / 降精度（fp16）
  → 降推理频率（每 3 帧 → 每 5 帧 → 关 ML）
  → 关实时效果（保留直出预览）
  → 降预览帧率（60→30）/ 降预览分辨率
```

触发信号三选一或组合：帧耗时滑动窗口超阈值、热状态档位（第 7 章）、掉帧计数。恢复要**滞回**（降级容易、恢复谨慎），防止在阈值附近来回切换造成"效果闪烁"。

### 4.5.4 红线

- 不在相机回调线程做处理（回调线程只入队）；
- 不在预览循环里 `glReadPixels`（同步回读阻塞 GPU 管线，必掉帧；导出/拍照按需读，或用 PBO 异步）；
- 不每帧 new 对象（FloatBuffer/Bitmap——GC 抖动直接体现在帧耗时的 p95 上）。

## 4.6 高帧率预览的约束链

高帧率不是"设个 FPS range"那么简单，约束是链式的（B 层）：

1. **设备能力**：`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 是否有 `[60,60]`/`[120,120]` 档，且**流尺寸越大越难保证**（sensor 读出 + ISP + DDR 带宽都翻倍）；
2. **曝光约束**：120fps 帧间隔 8.3ms → 曝光上限 8ms → 需要更亮环境或更高增益（噪声），暗光下高帧率形同虚设；
3. **下游链路**：TextureView + 滤镜在 120fps 下只剩 2~3ms 预算——通常要 SurfaceView 直通或极轻处理；
4. **功耗**：高帧率 + 高分辨率是发热大户（第 7 章），必须配降级策略与产品档位开关（用户可感知地选择，而不是偷偷全开）。

## 4.7 端到端延迟（capture-to-display）

测量三法（时间戳差值 / 高速摄像 / FrameTimeline）见第 2 章 2.6。这里讲**降低**手段与原理：

| 手段 | 原理 | 收益量级（B 层） |
|---|---|---|
| SurfaceView 硬件叠层 | 少一次 GPU 合成往返 | 0.5~1 帧 |
| 减少处理 pass / 丢旧帧 | 每帧处理时间 × 队列深度都算进延迟 | 每 pass 每帧；队列深度 n 就是 n 帧延迟 |
| `setDefaultBufferSize` 匹配 | 免显示端缩放 | 小 |
| 缩短缓冲队列 | BufferQueue 越深延迟越大（Buffer Stuffing 的反面） | 1~2 帧 |
| 降预览分辨率 | 带宽与处理时间同降 | 与分辨率平方成正比 |

给谁设预算：扫码（对焦帧到达即识别）、直播（推流延迟）、AR（motion-to-photon 通常要求 <20ms 级，C 层 AR 行业口径）——普通拍照 App 可放宽到 3~5 帧。

---

> 来源与延伸：[CameraX Preview（PERFORMANCE 模式/硬件叠层）](https://developer.android.com/media/camera/camerax/preview)、[PreviewView](https://developer.android.com/reference/androidx/camera/view/PreviewView)、[AE FPS range 语义](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#CONTROL_AE_TARGET_FPS_RANGE)、[Vitals 帧定义](https://developer.android.com/topic/performance/vitals)；帧预算数值、pass 预算与降级阶梯为通行实践（B 层）。GL 链路实现细节见《Android_Camera_算法集成文档》第 2 章；掉帧的 trace 判读见《Android_Camera_Trace分析文档》第 3~4 章。
