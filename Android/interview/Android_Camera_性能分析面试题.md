# Android Camera 性能分析面试题（指标 / 优化 / 体系）

> 与既有题集的分工：《Android_Camera_Trace面试题》讲**怎么用 trace 看数据**（工具视角），本集讲**相机性能的指标定义、优化手段与监控体系**（性能工程视角）；《Android_Camera_APP_面试题》Q36/Q65 与《Android_Camera_系统面试题》Q40 是单点考题，本集把它们展开成完整体系并补充内存/功耗/线上监控等空白域。
>
> 内容来源：一~八板块以本仓库文档为骨架；**板块九（Q37-Q46）为本仓库之外的公开资料扩展**——Android 官方 developer docs（FrameMetrics/JankStats、Thermal API、Android Vitals、Macrobenchmark）、androidx 库（metrics、camera-view）、Perfetto 官方文档、Play Console 公布的行为阈值，以及相机行业通行的 KPI 实践；不确定的细节均以"以官方文档为准"标注。
> 难度标注：★ 基础 / ★★ 中级 / ★★★ 高级（性能/系统方向加分项）。

## 目录

- [一、指标体系与度量](#一指标体系与度量)
- [二、启动性能](#二启动性能)
- [三、预览流畅性](#三预览流畅性)
- [四、拍照性能](#四拍照性能)
- [五、内存与资源](#五内存与资源)
- [六、功耗、热与稳定性](#六功耗热与稳定性)
- [七、线上监控与测试体系](#七线上监控与测试体系)
- [八、实战与开放题](#八实战与开放题)
- [九、扩展专题：公开资料与行业实践](#九扩展专题公开资料与行业实践)

---

## 一、指标体系与度量

### Q1. 相机 App 的核心性能指标有哪些？怎么分层？ ★★

按用户感知分层：

| 层 | 指标 | 定义 |
|---|---|---|
| 启动 | 冷/温/热启动首帧 | 点图标/进相机页/回前台 → 预览画面可见 |
| 流畅 | 预览帧率、Jank 率 | 掉帧次数占比、最长连续丢帧 |
| 拍照 | 快门延迟 / 出图延迟 | 点击→曝光开始 / 点击→照片可保存可分享 |
| 录像 | 起录延迟、录像帧率稳定性 | 点录制→首帧视频、录制中掉帧 |
| 资源 | 内存峰值、功耗、温度 | 长时间使用的 PSS/耗电/结温 |
| 稳定 | 崩溃率、ANR、被抢占率 | 相机相关 Crash/ANR/onDisconnected 次数 |

答题要点：先说"指标对应感知"（不是技术自嗨），再按层展开；每个指标必须有**明确起止点定义**，否则团队之间无法对齐（见 Q2/Q3）。

### Q2. "首帧时间"怎么定义？为什么冷/温/热启动必须分开统计？ ★★

首帧 = **预览画面第一帧真正上屏**（`onCaptureStarted` + 合成 + FrameTimeline 实际呈现），不是 `onOpened`/`onConfigured`。

- **冷启动**：进程被杀后启动——含进程创建、Application 初始化、provider 冷启动、完整 openCamera→配流链路；
- **温启动**：进程在、Activity 重建——省掉进程与 Application，但相机链路全走；
- **热启动**：后台返回、会话保留——只需 `setRepeatingRequest` 恢复，不应重走 openCamera。

分开统计的原因：三者瓶颈完全不同（冷启动大头在进程/provider；热启动若慢必是代码问题——重建了会话），混在一起会掩盖问题归属，优化也无法验收。

### Q3. 快门延迟和出图延迟的区别？各优化谁？ ★★

- **快门延迟**：点击 → `onCaptureStarted`（曝光开始），决定"按下有没有反应"——优化对象是 precapture 等待、请求下发路径、ZSL；
- **出图延迟**：点击 → 照片可保存/缩略图可显示，决定"多久能发出去"——优化对象是 HAL 多帧融合、JPEG/HEIF 编码、写盘、App 后处理。

常见错误：只优化编码（用户无感）而放任快门延迟（用户最敏感）；或反过来。正确做法是分段埋点（快门→曝光→结果→图像→保存），各自有预算。

### Q4. 预览流畅性怎么量化？掉帧率怎么算才公平？ ★★

- 帧率：相机侧 `onCaptureStarted` 间隔统计 vs 显示侧 FrameTimeline；
- Jank 率：掉帧帧数 / 总帧数（按"超过 1 个 VSync 间隔"计）；严重点位（连续丢帧 >3）单独统计；
- 公平性：**分场景统计**（亮光/暗光/前摄/后摄/高帧率模式），暗光掉帧是曝光物理约束（Q14），不能和亮光混算；采样时长要覆盖稳定态（去掉前 1s 冷启动段）。

### Q5. 相机的内存指标看什么？ ★★

- **PSS 峰值**：长时间预览 + 连拍 + 录像后的最大值（低端机红线常设 400~500MB 级别）；
- **native 增长趋势**：进相机/出相机循环，检测 buffer/纹理泄漏（是否回到基线）；
- **缓冲占用**：ImageReader maxImages × 单帧大小 + 各流 buffer（见 Q23）；
- 工具：`dumpsys meminfo <pkg>`、Android Studio Memory、Perfetto 的内存轨道。

### Q6. 功耗和热怎么度量？ ★★★

- 功耗：固定脚本（30min 预览/录像）下的耗电量（mAh 或 %）、平均电流（Battery Historian/厂商工具，工具链细节见 Q41）；
- 热：关键温度传感器曲线（surface/battery/skin）、热状态下帧率与出图延迟的衰减；
- 关联指标：热降频发生时间点、降级触发次数——性能预算要按"热稳态"而不是"冷机首次"设定。

---

## 二、启动性能

### Q7. 冷启动慢（首帧 1s+），你的排查路径？ ★★★

按链路分段定位（每段都有明确证据）：

1. **进程与 Application**：主线程长任务、SDK 同步初始化（Perfetto 主线程 slice）；
2. **openCamera → onOpened**：provider 是否冷启动（进程首次出现）、权限/驱逐；
3. **configureStreams**：流数量/尺寸、是否配流失败重试；
4. **首帧请求 → 上屏**：请求是否及时下发、SF 合成是否正常。

优先级由数据定：典型分布 provider 冷启动与 configureStreams 是大头，App 初始化其次。细节见《Android_Camera_Trace分析文档》第 3 章。

### Q8. 为什么 provider 冷启动是最大不确定性？能优化吗？ ★★★

provider 进程包含厂商 HAL：首次拉起要进程创建、加载 so、sensor 上电、固件加载、ISP 初始化，耗时几十到几百 ms 且**厂商/机型差异极大**，App 层不可控。可做的：

- 热启动路径**不释放会话**（返回前台直接恢复），减少触发冷启动的频率；
- 与系统团队合作保活/预热（平台侧）；
- 产品层接受"首次慢"，用启动动画掩盖并持续监控 p95。

### Q9. configureStreams 慢怎么优化？"两段式会话"是什么？ ★★★

- **减流**：首会话只配预览流，JPEG/分析流懒配置或后补——大流组合的配流耗时随流数/尺寸显著增长；
- **两段式**：先小流快速出首帧，`onConfigured` 后重建全量会话（把大流成本挪到首帧后，代价是重建瞬间预览闪断）；
- **预检**：`isSessionConfigurationSupported`（API 28+）/ `isStreamCombinationSupported`（API 29+）/ Android 15 `CameraDeviceSetup` 避免失败重试；
- **预览尺寸匹配**：预览流尺寸与控件匹配，避免多余缩放。

### Q10. 热启动（后台返回）恢复的正确姿势？ ★★

不重走 openCamera/建会话：`onPause` 只 `stopRunning` 级别的暂停（或保留会话），`onResume` 直接 `setRepeatingRequest` 恢复；被系统中断（onDisconnected）才重开。反模式：onPause 全量 close + onResume 全量重建——热启动退化成温启动，用户感知"每次切回来都卡"。

### Q11. 权限和设备枚举对首帧的影响？ ★★

- 权限弹窗是**用户交互**，耗时不可控，必须在进入相机前完成（入口页申请），相机页只做同步状态检查；
- `getCameraIdList`/`getCameraCharacteristics` 是 Binder 调用，首次还要建 listener 快照——启动页后台预热并缓存，相机页读缓存（注意授权前后 Key 可见性不同，需作废重取）。

### Q12. Baseline Profile 对相机 App 有用吗？ ★★

有用且收益显著：相机 App 关键路径（启动→openCamera 回调→首帧）是固定的 Java/Kotlin 代码，预编译成机器码可减少 JIT 冷路径；官方口径冷启动提升最高 ~30%。实操流程见《Android_通用面试题》Q41（生成器模块、`generateBaselineProfile`、profileinstaller）。

---

## 三、预览流畅性

### Q13. 预览掉帧的归因分类？ ★★

四分类（每类证据不同）：

1. **输出配置**：尺寸过大、JPEG stalling 流混入预览请求、流组合超带宽；
2. **帧时长约束**：暗光长曝光超过帧间隔（Q14）；
3. **缓冲**：消费端不归还 Image、buffer 池耗尽；
4. **线程/调度**：回调 Handler 阻塞、主线程卡顿影响渲染、CPU 降频。

工具组合：`dumpsys media.camera`（in-flight/丢帧计数）+ Perfetto（相机 track 与 Choreographer 对齐）。

### Q14. 暗光下预览帧率必然下降，为什么？ ★★★

帧时长 = max(曝光时间 + 读出时间, 帧间隔设置)。AE 为保证亮度拉长曝光（如 50ms），帧率上限 = 1000/50 = 20fps——物理约束不是 bug。`AE_TARGET_FPS_RANGE` 决定 AE 的取舍：`[30,30]` 锁帧率（曝光钳在 33ms 内，不够亮就加增益增噪）；`[10,30]` 允许降帧保画质。产品上暗光帧率策略要明确（夜景模式常主动降帧换长曝多帧）。

### Q15. 高帧率预览（60/120fps）的限制有哪些？ ★★★

- 设备能力：`CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 是否含高速档；流尺寸越大高帧率越难保证（带宽/ISP）；
- 自动曝光约束：帧间隔缩短 → 曝光上限被压 → 需要更亮环境或更高增益；
- 下游链路：编码器/显示合成能否跟上（TextureView + 复杂滤镜在 120fps 下预算只有 8.3ms）；
- 功耗：高帧率 + 高分辨率是发热大户，需配降级策略。

### Q16. GL 处理链（滤镜/美颜）的帧预算怎么定？怎么降级？ ★★★

预算 = 帧间隔 - 相机/显示开销余量：30fps 下算法+渲染 ≤8~10ms，60fps ≤4~6ms。实现要点：算法在降采样图上算、多 pass 用 FBO、丢旧帧只渲染最新、ML 每 N 帧推理一次复用结果。降级顺序：降算法分辨率 → 减 pass → 降推理频率 → 关效果 → 降帧率。触发信号：帧耗时监控 + 热状态（参考《算法集成文档》第 2 章 2.5）。

### Q17. SurfaceView 与 TextureView 的性能差异？ ★★

SurfaceView 独立合成层零拷贝直通（低延迟低功耗）；TextureView 是 View 树 GPU 纹理（支持动画变换但多一次上传、延迟与功耗高）。相机预览默认 SurfaceView；需要平移/缩放/透明动画叠加时才用 TextureView。详见《通用面试题》Q20 与《追问链》链 2。

---

## 四、拍照性能

### Q18. 拍照延迟怎么拆段？ ★★

五段：点击 → precapture/3A 收敛 → STILL_CAPTURE 请求 → 曝光（onCaptureStarted）→ 结果+图像回程 → 编码落盘。每段埋点分开统计；延迟下限由 pipeline depth 决定（≈ pipeline depth × 帧间隔）。详见《Trace分析文档》第 4 章。

### Q19. precapture 拖慢了快门，怎么办？ ★★★

precapture（AE 预闪收敛序列）服务于闪光/高品质场景，代价是多帧等待：

- 亮光/无闪光场景**跳过 precapture**，直接抓当前已收敛帧；
- 连拍场景**锁 3A** 复用收敛状态，省掉每张的收敛等待；
- 需要 precapture 时限制收敛帧数上限（超时降级直拍，宁快勿等）；
- 补偿方案：先出预览帧缩略图（秒回显），后台精修替换。

### Q20. ZSL 为什么能把快门延迟压到最低？ ★★★

ZSL 维持环形缓冲持续收预览帧（+ 每帧 `TotalCaptureResult`），按下快门时**取快门前已曝光的帧**做 reprocess——感知延迟≈0，因为帧在按快门前就完成了曝光。要求设备支持 `YUV_REPROCESSING`/`PRIVATE_REPROCESSING` 能力 + `createReprocessableCaptureSession`。无设备支持时退而求其次：跳过 precapture + 锁 3A + TEMPLATE_STILL_CAPTURE 直拍。

### Q21. 连拍间隔怎么优化？ ★★★

瓶颈 = 每张的 precapture + 请求串行 + 编码落盘排队。优化：锁 3A 连拍（省收敛）、请求队列化一次下发（burst）、`maxImages` 按连拍深度设置、编码与写盘异步流水线化（拍照不阻塞下一张请求）、降低单张分辨率档位（产品可选）。

### Q22. 拍照瞬间预览卡一下，为什么？ ★★★

JPEG 属于 **stalling stream**：编码占 ISP/编码器资源，与预览流共享管道，拍照请求处理的瞬间预览帧被挤占。缓解：拍照请求不带多余 target、降低 JPEG 尺寸档位、编码后移（先收 YUV 再异步编码）、厂商侧管线隔离（平台能力）。这是协议层特性，App 只能缓解不能消除。

---

## 五、内存与资源

### Q23. 相机 App 的内存大头在哪？怎么估算？ ★★★

大头是**像素缓冲**：

- 每帧大小 = 宽 × 高 × 每像素字节（YUV420 约 1.5B、RGBA 4B、RAW16 2B）；
- 例：12MP YUV ≈ 18MB/帧，12MP RAW16 ≈ 24MB/帧；ImageReader `maxImages=3` 就是 ~54MB；再乘多路流（预览+拍照+分析）；
- 估算公式：峰值 ≈ Σ(每流 maxBuffers × 单帧) + GL 纹理/FBO + 编码器缓冲 + App 后处理位图。

低端机要按可用内存反推：连拍深度、分辨率档位、分析流尺寸都从这里推导（参考《算法集成文档》第 4 章 4.4）。

### Q24. ImageReader 的 maxImages 怎么设？设大设小各有什么问题？ ★★

- 设小（1~2）：省内存，但"上一张没消费完、下一张已到"时会阻塞/丢帧——只适合低频拍照；
- 设大：连拍不阻塞，但内存线性增长（每张 = 一整帧）；
- 原则：**按消费速率设**——消费快（立即复制字节后 close）2 就够；连拍/慢消费（写盘慢）按并发深度设；任何情况下消费路径必须保证 close。

### Q25. 相机场景有哪些特有的内存泄漏？ ★★★

- **Image 未 close**：缓冲池耗尽（不是 GC 意义上的泄漏，但后果相同且更快）；
- **SurfaceTexture/Surface 未释放**：返回再进黑屏、native 缓冲累积；
- **会话/设备未 close**：持有整个相机管道资源，直到抢占；
- **相机回调持 Activity**：回调线程生命周期长于页面（Handler 泄漏的相机版）；
- **检测方法**：进/出相机页循环 10 次，看 native/PSS 是否阶梯增长（LeakCanary 对 native 缓冲无感，要靠内存曲线）。

### Q26. 多路流（预览+分析+拍照）的带宽怎么评估？ ★★★

带宽 ≈ Σ(每流 分辨率 × 帧率 × 每像素字节)。例：1080p30 预览（~93MB/s）+ 640×480 分析流（~13MB/s）+ 12MP JPEG 按快门频率。约束点：MIPI/ISP 总带宽、DDR 带宽、编码器吞吐。工程判断：分析流用最小可用尺寸与帧率；预览与分析不同时高帧率；不用的流及时从会话移除（重建会话）。

---

## 六、功耗、热与稳定性

### Q27. 相机为什么容易发热？降级矩阵怎么设计？ ★★★

热源：sensor + ISP 常开（每帧处理）、多路流带宽（DDR）、GPU 滤镜/NPU 推理、屏幕常亮高亮。降级矩阵挂在温度信号上（示例）：

| 温度档 | 动作 |
|---|---|
| 正常 | 全量（高帧率+滤镜+高分辨率） |
| 偏热 | 降帧率/关实时滤镜/降推理频率 |
| 高热 | 关效果、降预览分辨率、禁高帧率 |
| 临界 | 提示用户、限制录像时长 |

原则：降级要**可感知地告知**且**可恢复**（降温后逐级升回），避免反复横跳（滞回区间）。工程接入用系统 Thermal API（`PowerManager` 热状态监听与 `getThermalHeadroom` 预测，见 Q40），不要自己拿温度传感器读数猜档位。

### Q28. 长时间预览/录像的功耗怎么优化？ ★★★

- 流配置：满足体验的最小分辨率/帧率（预览不需要 4K）；
- 处理链：滤镜 pass 精简、降采样、丢帧渲染（处理帧率 < 显示帧率）；
- 硬件路径：预览走 PRIV 直通（不落 YUV）、编码用硬编、关闭不用的流；
- 系统：屏幕亮度策略、接近传感器灭屏、按场景切换帧率档；
- 度量固定脚本（30min）对比，不能凭体感。

### Q29. 相机相关的 ANR 场景有哪些？ ★★

- 主线程同步等相机回调（openCamera 结果轮询、同步锁）；
- ImageReader 消费线程被 IO 阻塞 + 主线程等待其结果；
- 权限/设置页跳转阻塞相机释放路径；
- 相机服务端卡死传导（CameraServiceWatchdog 杀进程重启期间，App 若同步等待会超时）。
预防：相机路径全异步 + 超时兜底 + onError/onDisconnected 完整处理。

### Q30. 相机被抢占（onDisconnected）怎么做好产品体验？ ★★

- 记录抢占来源与频率（线上指标），高频说明与其他功能冲突（如通话/安全扫描）；
- UI 上明确提示"相机被占用/已断开"而不是黑屏卡死；
- 监听 availability 回调，相机重新可用时自动恢复；
- 不要自动无限重试 openCamera（可能被系统判为异常行为），退避重试。

---

## 七、线上监控与测试体系

### Q31. 相机性能的实验室测试怎么搭？ ★★★

- **固定环境**：标准光照（暗光/亮光箱）、固定机型矩阵（芯片代 × 镜头组合）、温机与冷机分开、飞行模式固定后台负载；
- **脚本化**：uiautomator/Macrobenchmark 固定操作序列（冷启动/拍照/连拍/录像 10min）；
- **指标采集**：分段埋点（Q2/Q3 的定义）+ Perfetto 自动抓取 + dumpsys 快照；
- **统计口径**：每机型 N 次取中位数与 p95，对比基线版本出报告。

### Q32. 线上相机性能监控怎么设计埋点？ ★★★

- 分段耗时：首帧各阶段、快门/出图各阶段（自定义 trace 名与埋点对齐）；
- 状态计数：掉帧率（滑动窗口）、拍照失败率、onDisconnected 次数、热降级触发次数；
- 环境维度：机型/系统版本/前后摄/光照档（可用 AE 状态近似）/冷热启动；
- 采样策略：全量计数器 + 采样明细，异常（超阈值）时留存精简 trace 片段供回捞；
- 告警：版本对比的 p95 劣化超过阈值报警。

### Q33. 怎么做相机性能的自动化回归？ ★★★

CI 上跑脚本化用例（Q31），对每个提交/每日报对比基线：首帧 p95、快门 p50/p95、掉帧率、内存峰值。关键点：设备与环境的**一致性维护**（温度控制、基线更新流程）；劣化即阻塞合入；trace 与报告归档可回溯。没有自动化回归的性能优化都会在后续版本中悄悄退化。

---

## 八、实战与开放题

### Q34. 案例：冷启动首帧 1.6s，压到 800ms 以内，说说你的做法。 ★★★

答题框架（数据驱动，不是背清单）：

1. 分段测量定位：Activity 260ms / openCamera 540ms（provider 冷启动）/ configureStreams 380ms / 首帧上屏 160ms（示例）；
2. 按大头优化：热路径复用会话（避免 provider 冷启动）、首会话减流两段式（配流 380→150ms）、App 初始化异步化与权限前置；
3. 验收：同脚本 p95 对比 + 真机矩阵复测 + Baseline Profile 补充收益；
4. 防退化：分段埋点上线 + CI 回归。
（完整 trace 视角见《Trace分析文档》3.4 与《Trace面试题》Q26。）

### Q35. 案例：拍照出图要 800ms，用户抱怨慢，怎么分析？ ★★★

1. 拆段：快门 120ms（正常）/ precapture 350ms（暗光 AE 收敛多帧）/ 融合+编码 250ms / 写盘 80ms；
2. 判断各段合理性：precapture 在暗光是必要的（画质），但可设上限；编码 250ms 偏高（降 JPEG 档位/换硬编/异步化）；
3. 体验补偿：秒出预览缩略图 + 后台精修替换（延迟感知转移）；
4. 权衡陈述：快门优先还是画质优先要问产品，不是自作主张砍 precapture。

### Q36. 开放题：给你一个新相机 App，怎么从 0 建立性能基线？ ★★★

答题结构：**定义指标（Q1-Q3）→ 建测量（埋点+trace+实验室脚本）→ 定预算（各指标红线，按机型分档）→ 做防退化（CI 回归+线上监控告警）→ 持续优化（按数据排优先级）**。加分点：提到预算要随硬件代次演进、暗光/热稳态等"特殊态"单独建基线、指标定义文档化（团队对齐）。

## 九、扩展专题：公开资料与行业实践

> 本板块内容来自仓库之外的公开资料（Android 官方文档、androidx 库、Perfetto/Play Console 文档、行业通行实践），是对一~八板块的补充。

### Q37. 相机行业常用的启动/延迟 KPI 术语有哪些？ ★★

相机团队与 OEM 评测通行的指标口径（行业术语，非 Android 官方定义）：

| 术语 | 含义 |
|---|---|
| TTFF（Time to First Frame） | 相机启动到预览首帧，≈ 本文档的"首帧时间" |
| Time to First Shot | 启动到**第一张照片可拍出**（含 3A 就绪），比 TTFF 更苛刻 |
| Shot-to-Shot | 连续两张拍照之间的最短可接受间隔（含编码落盘） |
| Shutter Lag | 按快门到曝光（本文档"快门延迟"） |
| Capture-to-Display / Motion-to-Photon | 场景变化到屏幕呈现的端到端延迟（后者借自 AR 领域），见 Q39 |

答题加分点：知道这些口径并指出"面试时先和对方对齐指标定义，因为各家 TTFF 的起止点不一样"。

### Q38. 不用 Perfetto，App 内怎么做帧指标监控？FrameMetrics / JankStats ★★★

两条官方路线（公开 API/库）：

1. **`Window.OnFrameMetricsAvailableListener`**（API 24+）：系统按帧回调 `FrameMetrics`，可读 `TOTAL_DURATION`、`LAYOUT_MEASURE_DURATION`、`DRAW_DURATION`、`SWAP_BUFFERS_DURATION` 等分段耗时——正是 trace 里 doFrame 内部各阶段的程序化版本，适合线上采集帧耗时分布；
2. **androidx `JankStats`**（androidx.metrics 库）：在 FrameMetrics/Choreographer 之上封装，输出每帧 `FrameData(duration, isJank)` 与聚合统计，可自定义 jank 阈值（如 2 倍刷新周期）、挂 `OnFrameListener` 做线上上报——官方定位就是"生产环境的 jank 监控"。

相机 App 的用法：预览期只统计 UI 帧（滤镜页面/设置页），**预览画面本身不走 View 渲染**（SurfaceView 直通），所以 JankStats 反映的是相机 App 的 UI 卡顿而非相机出帧——两者要分开看（相机出帧用 `onCaptureStarted` 间隔统计，见 Q4）。

### Q39. 预览的端到端延迟（capture-to-display）怎么测？ ★★★

三层方法（由粗到精）：

1. **时间戳差值法（App 内可做）**：`SurfaceTexture.OnFrameAvailableListener` 拿到的 `SurfaceTexture.timestamp()` 是相机帧的传感器时间戳，与 `System.nanoTime()`（`SENSOR_INFO_TIMESTAMP_SOURCE=REALTIME` 时同基准）相减得到"曝光→App 可见"的管线延迟；再叠加合成/送显约 1~2 帧；
2. **高速摄像法（实验室）**：240fps+ 慢动作相机同时拍"被摄物运动"与"手机屏幕"，逐帧比对物像与屏像的相位差，除以帧间隔得 motion-to-photon 延迟；LED 闪光灯触发场景同理；
3. **FrameTimeline/呈现栅栏（系统侧）**：`actual_frame_timeline_slice` 的 `presented` 时间戳与期望呈现时间的差值，作为线上/实验室的权威口径。

意义：滤镜链、TextureView、GL 后处理都会增加 capture-to-display 延迟，直播/扫码场景对该指标敏感。

### Q40. Android 的 Thermal API 怎么接入相机降级？ ★★★

官方公开接口（替代"读温度传感器猜档位"）：

- `PowerManager.addThermalStatusListener(executor, listener)`（API 29+）：热状态回调，档位 `THERMAL_STATUS_NONE → LIGHT → MODERATE → SEVERE → CRITICAL → EMERGENCY → SHUTDOWN`；
- `PowerManager.getThermalHeadroom(forecastSeconds)`（API 30+）：预测"forecastSeconds 秒后"的热余量（0~1+），可提前降级而不是等 SEVERE 才反应——相机长录场景用"预测值 + 状态回调"双信号；
- 接入模式：状态变化映射到 Q27 的降级矩阵（LIGHT 降帧率 → MODERATE 关滤镜 → SEVERE 降分辨率/禁录像），恢复用滞回（降一档容易、升一档谨慎）；
- 注意：档位由系统按整机状态判定，相机不一定是最热源——降级了相机还热，要反馈系统侧（其它 App/充电）。

### Q41. 相机功耗测量的工具链有哪些？怎么选？ ★★★

| 工具 | 层级 | 用法与适用 |
|---|---|---|
| `adb shell dumpsys batterystats` + Battery Historian | 系统/软件 | 固定脚本跑完导出，看耗电归因（wakelock/传感器/屏幕/CPU 频率）、Camera 使用时段；适合对比版本间差异 |
| Android Studio Energy Profiler | 开发期 | 可视化 CPU/网络/定位耗电事件，定位"谁在耗电"快速验证 |
| Perfetto `power.rails` 数据源 | 硬件级 | **部分设备**（如 Pixel）可采实际电流轨（VDD 等），per 硬件块的真功耗；配合 camera track 做"哪段管线耗电"归因 |
| SoC 厂商工具（如 Snapdragon Profiler 等） | 硬件级 | CPU/GPU/DDR 频率与功耗联动分析，平台调优常用 |
| Monsoon 等外接功耗仪 | 物理级 | 金标准：直接测整机电流，实验室基准与标定用 |

工程建议：日常回归用 batterystats（可脚本化），深度归因用 power rails/厂商工具，发布前基准用外接功耗仪标定一次。

### Q42. CameraX 有哪些影响性能的配置？ ★★

基于 androidx 公开文档/最佳实践：

- **`PreviewView` 的显示模式**：`PerformanceMode.PERFORMANCE_MODE`（默认）优先 SurfaceView 零拷贝；`COMPATIBLE_MODE` 用 TextureView（兼容动画/变换，代价是延迟与功耗）；新版本可直接控制 implementationMode（SurfaceView/TextureView，以所用版本文档为准）；
- **`ImageAnalysis` 背压策略**：`STRATEGY_KEEP_ONLY_LATEST`（丢旧帧，实时分析首选）vs `STRATEGY_BLOCK_PRODUCER`（保帧，积压风险）——分析消费慢时选错会拖垮整条链；
- **分辨率**：`ResolutionSelector` 把分析流压到最小可用（640 级），不要默认分辨率起步；
- **`Camera2Interop` 陷阱**：往预览请求塞 STILL_CAPTURE 级参数（如高质量降噪）会拉低帧率；跨 interop 改动要在真机矩阵验证（OEM 行为差异）；
- 升级注意：CameraX 各版本对分辨率协商与扩展（Extensions）的行为有变化，升级后要跑性能回归。

### Q43. 长时间录像的资源纪律是什么？ ★★★

公开实践汇总：

1. **前台服务 + wake lock**：Android 14+ 必须声明 `foregroundServiceType="camera"`（+ `mediaPlayback` 按需）并申请对应权限；`PARTIAL_WAKE_LOCK` 保持 CPU，录像结束必须释放（Vitals 监控 wake lock 滥用）；
2. **编码器**：硬编（surface 输入）+ 合理码控（VBR/码率上限），热降级时主动降码率/分辨率而不是等编码器丢帧；
3. **存储**：写盘用独立线程 + 合理缓冲，避免写盘阻塞帧消费（D 状态拖垮整链，见 Q22/Q24）；满盘/慢速存储要有预判（剩余空间检查）；
4. **热联动**：编码器是发热大户，接 Q40 的热信号提前降档；
5. **后台切前台的连续性**：录像中切后台（画中画/分屏）时保持服务，不要因生命周期误停录像。

### Q44. Android Vitals / Play 对相机 App 有哪些性能约束？ ★★

Play Console 核心行为指标（公开定义）：

- **Slow frames**：单帧渲染 >16ms 的占比（约一个刷新周期）；
- **Frozen frames**：单帧 >700ms（用户感知"冻住"）；
- **用户感知 ANR 率 / 崩溃率**：前台 ANR 对话框与崩溃的会话占比，超阈值影响商店推荐（具体百分比阈值以 Play Console 当前公布为准，会调整）；
- **Wake lock 滥用**：部分唤醒锁持有超时会被标记。

对相机的映射：相机打开路径的卡顿会直接推高 slow/frozen frames 与 ANR 率（主线程等相机回调是最常见来源，见 Q29）；录像 wake lock 不释放会被标记。建议把 Vitals 阈值当作**外部红线**纳入 Q32 的线上监控，而不是只看自家埋点。

### Q45. 录像性能的指标与优化清单？ ★★★

指标：起录延迟（点录制→首帧视频入码）、录制帧率稳定性（p95 帧间隔）、丢帧数、音画同步偏移（ms）、热稳态下的码率/帧率衰减、录像中拍照的干扰（掉帧/音画跳变）。

优化清单：

- 起录：编码器预热（进入相机页就初始化 MediaCodec 等待 surface）、首帧等待策略（起录瞬间允许丢帧不允许花屏）；
- 稳定：帧率锁定（`AE_TARGET_FPS_RANGE` 下限）、预览/录制流分担（录制流 PRIV 直通编码器，不过 App 手）；
- 音画：音频/视频时间戳同基准（`SENSOR_INFO_TIMESTAMP_SOURCE=REALTIME` 时与 monotonic 对齐），muxer 起点取首帧最小 PTS；
- 资源：硬编 + 码控自适应；录像中拍照用 VIDEO_SNAPSHOT 模板/独立低分辨率流，避免大 JPEG 挤占带宽；
- 热：按 Q40 预测提前降码率（用户对码率下降的感知 < 对掉帧的感知）。

### Q46. 怎么用 Macrobenchmark 给相机 App 做基准测试？ ★★★

 androidx 官方基准库的公开能力：

- **`StartupTimingMetric`**：冷/温启动的 TTID/TTFD（初始/完整显示），直接对应相机"首帧"口径的一部分（完整首帧还要叠加相机埋点）；
- **`FrameTimingMetric`**：滚动/动画期间的帧耗时分布（p50/p90/p95/p99），适合衡量相机设置页、相册页的流畅性；
- 用法：`@BenchmarkMode` 跑固定 uiautomator 脚本（进相机→切模式→录像 30s→退出），非 debuggable、release 优化构建（`benchmark` buildType），多次迭代取分布；
- 与 Q31/Q33 的关系：Macrobenchmark 做实验室/CI 的**可复现测量**，线上监控（Q32）做真实环境采样，两者互补构成完整证据链；
- 限制：对"相机出帧速率"这类非 View 渲染指标没有直接 metric，需在 demo 层（被测 App 内）埋点导出，或在脚本里读 dumpsys 计数。

---

> 配套阅读（仓库内）：《Android_Camera_Trace分析文档》（数据采集与归因）、《Android_Camera_算法集成文档》第 2/4 章（预览预算与内存）、《Android_Camera_APP_面试题》Q36/Q57/Q65、《Android_Camera_系统面试题》Q40、《Android_Camera_Trace面试题》（工具细节）、《Android_通用面试题》Q41（Baseline Profile 实操）。
> 配套阅读（仓库外·公开资料）：Android 开发者文档（[JankStats](https://developer.android.com/topic/performance/vitals/jank-stats)、[FrameMetrics](https://developer.android.com/reference/android/view/FrameMetrics)、[Thermal API](https://developer.android.com/about/versions/11/features#thermal)、[Android Vitals](https://developer.android.com/topic/performance/vitals)、[Macrobenchmark](https://developer.android.com/macrobenchmark)、[Camera 性能建议](https://developer.android.com/media/camera)）、androidx [camera-view](https://developer.android.com/reference/androidx/camera/view/PreviewView) 与 [metrics](https://developer.android.com/jetpack/androidx/releases/metrics) 库、[Perfetto 文档](https://perfetto.dev/docs/)、Play Console 行为阈值页。
