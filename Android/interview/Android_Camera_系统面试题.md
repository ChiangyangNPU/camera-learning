# Android Camera 系统面试题（framework / HAL / ISP 进阶）

> 与《Android\_Camera\_APP\_面试题》互补的一份：面向 **相机系统工程师 / framework / HAL / tuning** 岗位的深挖题。依据本仓库《Android\_Camera\_源码链路文档》（AOSP main 分支逐行核对）、《Android\_Camera\_ISP\_3A文档》整理，答案均可在对应文档找到出处。
> 难度标注：★★ 中级 / ★★★ 高级（本题集以 ★★★ 为主）。

## 目录

* [一、CameraService 与框架深挖](#一cameraservice-与框架深挖)
* [二、HAL3 协议与工程实践](#二hal3-协议与工程实践)
* [三、ISP 与 3A 算法工程](#三isp-与-3a-算法工程)
* [四、平台架构与调试实战](#四平台架构与调试实战)

***

## 一、CameraService 与框架深挖

### Q1. cameraserver 进程是怎么启动的？CameraService 如何注册进系统？ ★★★

init 解析 `cameraserver.rc` 启动 `main_cameraserver.cpp`：进程先初始化 Binder 线程池（`ProcessState::self()->startThreadPool()`），再 `sp<ProcessState> ps = ProcessState::self()` 后调用 `CameraService::instantiate()`——内部把 CameraService 以标准 Binder 服务名 `media.camera` 注册到 ServiceManager，随后 join 线程池常驻。与 mediaserver 时代不同，main 分支上 cameraserver 是独立进程（与 audioserver/mediaserver 分离），崩溃隔离更好。

### Q2. APP 调 getCameraIdList 时，框架真的每次都去问 HAL 吗？ ★★★

不是。Java 侧 `CameraManager.getCameraIdList()` 读的是 **`CameraManagerGlobal` 单例缓存**的设备列表。该列表的建立与维护：APP 首次访问时 `ICameraService.addListener()` 一次拿到**当前快照**，之后靠 provider 的 `ICameraProviderCallback`（设备状态回调）增量更新。这解释了两个现象：设备热插拔时 APP 收 `onCameraAvailable/Unavailable`；旧资料里"getCameraIdList 走 binder 查询"的说法是错的（可对照源码链路文档第 1 章）。

### Q3. CameraService 的 client 优先级具体怎么算？什么情况下会驱逐？ ★★★

每个 client 有 `ClientPriority`（score + state）。score 来源：UidPolicy 监听 UID 的 proc 状态（前台/后台/空闲）+ ProcessInfoService 的 oom adj 实时刷新；state 区分 ACTIVE（正在处理请求/录制）与 IDLE。驱逐发生在新 open 与资源冲突时：`ClientManager` 按 (state, score, clientId) 排序，低优先级 client 被调 `disconnect` 并回调 `onDisconnected`。典型规则：前台 ACTIVE > 前台 IDLE > 可见 > 后台；同分先到先得。追问常考点：**录制中的 client state 是 ACTIVE，比同为前台的预览 client 更不容易被踢**。

### Q4. setTorchMode 为什么不需要 openCamera？它的链路和普通 client 有何不同？ ★★★

手电筒被设计为"无 client 的轻量控制"：`CameraService::setTorchMode` 不创建 CameraDeviceClient，直接透传到 provider 的 flash 节点（HAL `setTorchMode` op），并用引用计数管理并发（同一相机 torch 与 open 互斥——被 client 占用时 setTorchMode 返回 CAMERA\_IN\_USE）。状态通知走独立的 `torch status callback`（onTorchStatusChanged）。设计动机：手电筒是高频、系统级入口（快捷磁贴），不能受 client 数量与优先级驱逐影响。

### Q5. 请求/结果元数据为什么要用 FMQ 传输？相比普通 Binder 省了什么？ ★★★

Binder 每次事务把 parcel 数据从调用方拷贝到内核再到目标进程；一帧请求/结果元数据可达数 KB（含 vendor tag），60fps 下仅元数据就有数十次拷贝。AIDL/HIDL 相机接口对元数据用 **FMQ（快速消息队列，基于共享内存 + eventfd 唤醒）**：元数据写入共享内存环形区，Binder 事务只传一个轻量句柄（描述符 + fence），零拷贝传递。配套的还有 native\_handle 直接传递 fd（缓冲区 fd 跨进程 dup）。面试要点：**控制面走 Binder、数据面走共享内存/fd** 是相机框架传输设计的总原则。

### Q6. CameraServiceWatchdog 解决什么问题？怎么实现？ ★★★

HAL 调用可能因 vendor 实现缺陷卡死（如 configureStreams 永不返回），把 CameraService 主线程拖死。Watchdog 以看门狗线程定期探测：CameraService 的关键路径（monitor 回调注册）在执行 HAL 调用前"喂狗"，超时未喂则认为卡死，`killProcess` 自杀——cameraserver 由 init 自动重启并重新绑定 provider，实现服务级自愈。APP 侧表现是相机短暂不可用后恢复。追问：provider 进程另有独立的 binding 崩溃检测（death recipient），两级自愈对象不同。

### Q7. main 分支的 libcameraservice 如何同时支持 AIDL 和 HIDL provider？ ★★★

源码链路文档第 1 章的结论：适配层独立成目录——`libcameraservice/common/hidl/HidlProviderInfo` 与 `common/aidl/AidlProviderInfo` 负责把两种 provider 的设备描述归一化为框架内部结构；设备侧 `device3/` 下 `Camera3Device` 的传输封装按 aidl/hidl/deprecated 拆分。框架核心（ClientManager、CameraDeviceClient、请求调度）完全 unaware，靠归一化接口隔离协议差异。追问：为什么 Android 13 后新功能只在 AIDL 提供（稳定 AIDL 可版本化、性能与可扩展性、HIDL 冻结）。

### Q8. CameraProviderManager 怎么发现与加载 provider？外接相机走哪条路？ ★★★

服务启动时通过 hwservice/service manager 列出 `android.hardware.camera.provider` 的全部实例逐个连接；连接后调 `getCameraIdList` + 每设备 `getCameraDeviceInterface` 建 ProviderInfo（HIDL/AIDL 各有实现）。外接 USB 相机由 AOSP 自带的 **ExternalCameraProvider**（provider/default 下）实现，把 /dev/video\* 映射为 EXTERNAL 级别的相机（对齐学习文档第 4 章版本表）。内置相机的 provider 实现则下沉在各设备 vendor 分区。

### Q9. 从安全视角看，CameraService 对 APP 和对 HAL 分别设了哪些防线？ ★★★

* 对 APP（不可信）：权限校验（`checkCameraAccess`，含 SYSTEM\_CAMERA 特权检查）、uid/pid 记录、client 数量上限、参数合法性（Surface 尺寸/格式由框架侧初步校验）；
* 对 HAL（安全边界）：HAL 在独立进程且是"半可信 vendor 代码"，框架传给 HAL 的**缓冲区长度、fence、请求结构必须由 HAL 自行二次验证**（官方文档明确该边界责任）；缓冲区 fd 通过 Binder 传递，杜绝 APP 越权拿到其他进程的图像；
* 进程隔离：cameraserver 与 provider 分离，任一崩溃不直接影响对方数据。

### Q10. 并发打开两个相机的资源仲裁在框架里怎么实现？ ★★★

两条路：一是 `getConcurrentCameraIds()` 显式给出的"保证并发组合"（provider 在静态元数据里声明）；二是无显式组合时的运行时仲裁——每个相机有 resource cost，CameraService 汇总当前打开 client 的成本，超过平台上限则拒绝 open（MAX\_CAMERAS\_IN\_USE）。高分辨率双摄会话还要过 `isConcurrentSessionConfigurationSupported` 的流组合校验。追问：为什么硬件层只保证有限组合（MIPI 通道/ISP 管道共享）。

### Q11. SYSTEM\_CAMERA 是什么？谁能用？ ★★★

Android 12 引入的特权能力（`REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA`）：持有 `android.permission.SYSTEM_CAMERA` 特权权限（system/priv-app 且 privapp-permissions 白名单）的 App 才能打开，典型用户是系统相机/安全类系统应用；普通 App 的 getCameraIdList 会直接过滤掉这些设备（API 32+ 可经 `getKeysNeedingPermission` 类机制感知其存在性受限）。设计目的：把受限相机（如红外/内部监控流）从三方可见面里摘出去。

### Q12. 离线处理会话（offline session）解决什么问题？ ★★★

Android 11 引入：切会话/关相机时，把**已提交未完成**的请求及其缓冲区移交给 `ICameraOfflineSession` 继续处理，释放在线设备给新 client。典型场景：连拍过程中用户切模式，剩余照片后台出图。要求 HAL 支持 `offline_processing` 能力；框架侧 Camera3Device 拆分在线/离线流。追问：与 APP 层"deferred photo processing"（iOS 概念，见 iOS 系列）的概念区分——Android 离线会话是框架协议级机制，不是成像质量特性。

***

## 二、HAL3 协议与工程实践

### Q13. configureStreams 时框架和 HAL 各自能改什么？典型的协商结果是什么？ ★★★

框架下发 `StreamConfiguration`（目标流列表 + 操作模式 + 会话参数）。HAL 拥有的协商空间：修改 `gralloc usage`（如叠加隐私约束）、调整 `max_buffers`（≥ 框架要求）、把 `PRIV` 格式流映射为实现定义格式（相机独占 PRIVATE 队列路径）、在受限组合上报错。返回的配置是**最终生效契约**，框架不得再改。典型结果：预览流 PRIV 走零拷贝直通 SurfaceFlinger，YUV 流按 ISP 最低对齐放宽尺寸（宽高对齐到 16/64）。

### Q14. 解释 acquire fence 与 release fence，缓冲同步为什么不用锁？ ★★★

每块 `camera3_stream_buffer_t` 携带一对 sync fence：**acquire fence** 由框架在把缓冲交给 HAL 时附带，表示"上一个使用者（消费者侧）已读取完毕"，HAL 等 fence 触发后才可写入；**release fence** 由 HAL 在归还缓冲时设置，表示"本方已写完"，下游消费者等它触发后才可读——合起来是"写者等读完成、读者等写完成"的乒乓同步。这是 sync framework 的跨进程 fd 语义，GPU/ISP/显示控制器各自在硬件上等待。不用锁的原因：参与者跨进程跨硬件，阻塞等待会拖死调度；fence 把"同步点"下沉到硬件，CPU 零等待。追问：fence 状态错误（过早 release）的典型表现——花屏/半帧。

### Q15. 为什么请求不能无限下发？pipeline depth 是什么？ ★★★

HAL 内部是有限深度的流水线（曝光→读出→ISP 各 stage 占位），`ANDROID_REQUEST_PIPELINE_MAX_DEPTH`（典型 4\~8）声明上限，`ANDROID_REQUEST_PIPELINE_DEPTH`（result 里）回报实际深度。框架据此限制 in-flight 请求量——超出会导致缓冲池耗尽与结果乱序加剧。拍照延迟的下限由此决定：**单张照片至少等待一个 pipeline depth 的流水线排空**，这正是 ZSL 的优化空间（结果在按下快门前已在流水线里）。

### Q16. partial result 的合规规则是什么？为什么框架要区分 partial 与 final？ ★★★

HAL 可把一帧元数据拆多次返回（`partialResultCount` 声明最大份数），但**每份必须携带部分唯一且最终一致的结果**（官方规则：先回的 partial 里给出的值不许在后续被推翻；3A 状态等可早回）。框架把 partial 累积成 `TotalCaptureResult` 才回调 onCaptureCompleted。动机：3A 早回使上层提前响应（如对焦完成指示），重统计晚回不阻塞。工程坑：vendor 常犯"partial 里给最终值、final 又改"的违规，ITS 有对应用例。

### Q17. 帧时间戳有哪些语义约束？多流怎么保证一致？ ★★★

`SENSOR_TIMESTAMP`：**该帧第一行开始曝光的时刻**（官方定义 start of exposure of the first row，不是读出时刻），基于单一单调时钟（`SENSOR_INFO_TIMESTAMP_SOURCE`：REALTIME 对齐 SystemClock.elapsedRealtimeNanos，UNKNOWN 为未定义基准）。约束：同一请求的所有输出流必须**同一时间戳**；时间戳单调不减。实现上 HAL 对每个 buffer 附相同的 `sensor_capture_time`。APP 侧价值：音视频对齐、多摄帧配对（物理流结果带时间戳可匹配融合帧）。

### Q18. flush() 做了什么？与单流 signalStreamFlush 区别？ ★★★

`flush`：HAL 丢弃全部在途请求，对每个在途 buffer 上报 ERROR\_REQUEST/ERROR\_RESULT，fence 正常 release（不能吞 fence，否则消费端永久阻塞）；用于急停、模式切换、错误恢复。Android 10 的缓冲管理 API 里 `signalStreamFlush` 是**定向刷新**：框架即将从某流回收缓冲（如 ImageReader 关闭），HAL 尽快归还该流缓冲，不动其他流。两者对象不同：会话级全量 vs 指定流。

### Q19. constructDefaultRequestSettings 是干什么的？为什么必须由 HAL 实现？ ★★★

框架给每个模板（PREVIEW/STILL\_CAPTURE…）要一份"HAL 认可的默认元数据"。由 HAL 实现是因为**默认值依赖硬件特性**（如 LEGACY 设备的 AE 目标、FULL 设备的手动默认）：同一模板在不同 HAL 上展开的具体键值不同。框架用它初始化请求；APP 在 Builder 上的覆盖以这些值为底。追问：为什么 app 不能假设模板值固定——版本与厂商差异。

### Q20. reprocess 在 HAL 协议层长什么样？Private reprocess 和 YUV reprocess 的差别？ ★★★

配流时声明**输入流**（InputConfiguration）；请求的 `input_buffer` 非 NULL 即 reprocess 请求，`settings` 必须来自该图原始捕获的 TotalCaptureResult（保证可用性元数据一致）。差别：YUV reprocess 输入 IMAGE\_FORMAT\_YUV\_420\_888（需 LEVEL\_3），Private reprocess 输入 PRIV（ZSL 队列的私有格式，允许 HAL 直通 ISP 内部表示，效率更高）。追问：为什么 reprocess 请求的 timestamp 语义特殊（沿用原始捕获时间）。

### Q21. Android 10 的 HAL 缓冲区管理三方法分别解决什么？ ★★★

传统模式：框架为每流预分配 max\_buffers 并在请求里全部填好——峰值内存 = Σ(每流 max × 缓冲大小)，4K 多流场景数亿字节。新模型：HAL 按需 `requestStreamBuffers` 要缓冲、处理完 `returnStreamBuffers` 归还；框架通过 `signalStreamFlush` 在回收前通知 HAL 清空。内存从"按峰值预分配"变"按需流动"。代价：HAL 管理复杂度上升，fence 与错误处理（BUFFER\_MANAGEMENT 错误码）必须完备。

### Q22. RAW16 / RAW\_OPAQUE / RAW10 / RAW\_SENSOR 的区别？黑白电平在哪定义？ ★★★

`RAW_SENSOR`（16 位按 16 存）与 `RAW10`/`RAW12`（MIPI 打包，带宽优化）是标准 raw；`RAW_OPAQUE` 是实现定义的私有 raw（仅 reprocess 输入用）。黑白电平由 `SENSOR_BLACK_LEVEL_PATTERN`（四通道，Bayer 位置各值）与 `SENSOR_INFO_WHITE_LEVEL`（典型 1023/4095）描述；动态黑电平（高增益下抬升）在 result 的 `SENSOR_DYNAMIC_BLACK_LEVEL` 回报——ISP 与后期处理必须用它而不是静态值。追问：为什么 G 通道两个位置的黑电平可能不同（列噪声标定差异）。

### Q23. 高速会话（120/240fps）的限制为什么是协议强制的？ ★★★

高速模式本质是"锁定 AE 的固定曝光 + 极短帧时长"——曝光时间被压到毫秒级以下时 AE 调节空间消失、ISP 后处理预算不够，因此协议规定高速会话只允许两路流（预览+录像）、仅特定尺寸/帧率组合、3A 行为受限。`CONSTRAINED_HIGH_SPEED_VIDEO` 能力 + 专用会话类型保证 HAL 可以用专门流水线（跳过部分后处理 stage）实现。

### Q24. SCALER\_STREAM\_USE\_CASES 是什么？解决什么痛点？ ★★★

Android 13 引入的流用途标注（PREVIEW/VIDEO\_SNAPSHOT/STILL\_CAPTURE/VIDEO\_RECORD/PREVIEW\_VIDEO\_STALL…）。痛点：此前 HAL 只见"尺寸+格式"，无法区分"这个 1080p YUV 是预览还是分析"，只能保守配置。标注用途后 HAL 可按用例选流水线（如 VIDEO\_STREAM vs STILL\_CAPTURE 走不同降噪），并给出优化的组合保证。APP 侧配合 `OutputConfiguration.setStreamUseCase` 使用；与 session parameters 一起构成"给 HAL 更多意图信息"的演进主线。

***

## 三、ISP 与 3A 算法工程

### Q25. AE 的分区测光是怎么工作的？权重怎么设计？ ★★★

ISP 把全帧划分为网格分区（常见 64\~256 区，硬件实现），输出每区直方图/均值到 AE 统计。收敛时按加权目标亮度迭代：分区权重融合了**场景先验**（中心加重）与**语义信息**（有 face metadata 时人脸区加权，触摸点区域临时加权对应 APP 的 exposurePointOfInterest）。追问点：为什么不用全帧均值（逆光人脸会欠曝）；闪光灯决策如何从分区分布判断（暗区比例 vs 全局均值）。

### Q26. 防闪烁（antibanding）除了设曝光整数倍还有什么实现层面？ ★★★

两层机制：一是**曝光约束**——把 exposure time 钳到 1/(2×工频) 的整数倍（50Hz→10ms 整数倍），由 AE 在生成曝光表时执行（`AE_ANTIBANDING_MODE`）；二是**闪烁检测**——`STATISTICS_SCENE_FLICKER` 由 ISP 在卷帘方向做行间亮度频谱分析（100/120Hz 分量显著则上报），提示 AE 当前存在闪烁风险。高级实现（C 层通行做法）：对每行统计做 FFT 或自相关，检出频率后动态选择避开频段的曝光档位。

### Q27. LSC（镜头阴影校正）的标定流程与标定产物？ ★★★

产物是**网格化增益表**（典型 17×13 或更细，R/Gb/Gr/B 四通道各一张）。流程：均匀光源（多色温：D65/TL84/A）下拍摄均匀白板 → 取每格实际均值与目标值的比值作为增益 → 平滑拟合 → 存入 tuning 数据（高通 Chromatix / MTK 参数文件），运行时按色温在表间插值。追问：为什么必须按色温分表（CRA 与红外截止滤片在不同光谱下的渐晕不同）；为什么 G 通道拆 Gr/Gb（Bayer 两个 G 位置响应不对称）。

### Q28. 噪声模型怎么标定？AE 和降噪怎么用它？ ★★★

传感器噪声 ≈ 泊松（散粒）噪声 + 高斯（读出）噪声：σ² = a·S + b（S 为信号，a 与增益相关，b 为读出偏置）。标定方法：均匀灰阶多档曝光/增益下拍摄，统计各档均值-方差对，线性回归出 (a, b)。用途：AE 的合并策略（低光下时长 vs 增益的画质权衡）、降噪强度自适应（`SENSOR_NOISE_PROFILE` 元数据按增益回报噪声系数，供 NR 与后续 AI 处理使用）、RAW 后期软件的降噪。

### Q29. 黑电平为什么会"动态"？不校正会怎样？ ★★★

像素阵列的暗电流与列电路偏置随增益/温度漂移：静态标定的黑电平在高增益长曝光下会偏小（画面整体偏灰、暗部发雾）。所以 result 回报 `SENSOR_DYNAMIC_BLACK_LEVEL`。不校正的后果：黑不是黑（低光夜景噪点抬升）、AWB 统计被污染（所有通道同量偏移本可抵消，但列间不一致会引入色带）。追问：BPC（坏点）与黑电平的先后——先动态黑电平后坏点判据（阈值依赖正确的黑基准）。

### Q30. 去马赛克算法有哪些层次？RAW 画质的分水岭在哪？ ★★★

三层递进：**双线性**（邻域平均，拉链伪影重）→ **自适应方向插值**（沿边缘梯度加权，如 EDGE-AWARE/梯度修正）→ **基于假设检验的算法**（如 Resinging 类，模拟重采样防伪色）。RAW 画质分水岭在伪彩（mauve/color moiré）与拉链纹：高质量去马赛克在边缘处用跨通道结构信息而非纯同通道插值。Android 侧相关元数据：`SENSOR_INFO_COLOR_FILTER_ARRANGEMENT`（决定插值核形状，RGGB/BGGR/GRBG/GBRG 四相）。

### Q31. CCM 怎么标定？为什么一个 CCM 不够用？ ★★★

标定：标准光源下拍 24 色卡，对每个色块的相机 RGB 与目标 sRGB（或指定空间）做**最小二乘**求 3×3 矩阵。单一 CCM 不够：传感器光谱响应偏离人眼（青色缺失等），固定矩阵只能在单一光源下兼顾——所以 ISP 按**光源簇**准备多组 CCM（D65/TL84/A），AWB 判定光源后插值选择。追问：CCM 与 AWB 增益的顺序（先 WB 增益去色偏，再 CCM 做饱和度/色域映射）；对角化约束（保持灰轴不动）。

### Q32. Gamma 与 tone mapping 在 ISP 里各做什么？HDR 视频的 tone mapping 有何不同？ ★★★

Gamma：CRT 遗留的传递函数补偿（sRGB EOTF），对 RAW 线性数据做亮度非线性化以匹配显示与视觉感知。Tone mapping：更宽域的亮度压缩策略（局部自适应，保护高光/提暗部），Smart HDR 类多帧合成的输出进入显示域必经此步。HDR 视频（HLG/PQ）：HLG 的相对语义允许 SDR 兼容显示，PQ 绝对亮度需元数据；拍摄端 tone mapping 目标从"显示好看"变为"编码域正确"。追问：为什么拍照的 tone mapping 不能直接用于视频（帧间稳定性，闪烁/呼吸）。

### Q33. 反差对焦的爬山细节？怎样避免拉风箱？ ★★★

以 AF 统计（锐度度量，如高频能量/归一化方差）为曲线，步进驱动镜头找峰值。防拉风箱三件套：**步长自适应**（远峰大步、近峰小步）、**方向判定去抖**（连续 N 次下降才回头，配合置信度）、**历史位置缓存**（记录典型焦距，先跳最近似位置）。Android 无标准 AF 曲线元数据（厂商私有），但 `LENS_FOCUS_DISTANCE` 与 result 的 lens state 可用于上层复现行为分析。

### Q34. PDAF 标定都标什么？置信度怎么用？ ★★★

标定项：PD 像素的**偏移基线**（左右像分离度与离焦量的映射表，按焦距/光圈/温度补正）、**遮蔽位置表**（shielded pixels 在 active array 的坐标）、PD 与 CDAF 曲线的融合权重。置信度：低纹理/弱光区 PD 相关峰值不显著，算法按置信度加权 PD 与 CDAF 输出（高置信直接推镜头，低置信退回爬山）。追问：为什么 PDAF 暗光失效（信噪比不足致相关峰淹没）；全像素双核 PD 与掩蔽式 PD 的差异。

### Q35. 双摄/多摄的帧同步怎么做？不同步会怎样？ ★★★

协议层：主 sensor 输出同步信号（VSYNC/XVS/XHS）给从 sensor，从机按主机节拍曝光（可配相位偏移）；`SYNC_MAX_LATENCY` 元数据声明同步方式（PER\_FRAME\_CONTROL 每帧可调 vs AT\_ONCE）。不同步的后果：双摄融合（bokeh/变焦切换）时两图间物体位置错动，产生鬼影或模糊边缘；视频录制时画面跳变。追问：逻辑多摄切换时 AE/AF 状态如何交接（主从角色切换、增益匹配）。

### Q36. 为什么 3A 统计都在 RAW 域做？哪些统计必须在 RGB/YUV 域？ ★★★

RAW 域统计的优势：未经去马赛克/CCM 的"真"信号，噪声模型已知（可用噪声感知统计）、带宽小、且对 AE/AWB 的物理量（曝光/色温）语义最直接。必须在后段的：**AF 锐度统计**（需接近显示的对比度表达，去马赛克后）、人脸/主体检测（NPU 输入）、纹理增强反馈。追问：LSC 在统计前还是后（前——先消除渐晕偏差，统计才代表场景）。

***

## 四、平台架构与调试实战

### Q37. CAMX 的 UseCase / Pipeline / Node 三层是什么关系？ ★★★

* **UseCase**：业务意图（预览+拍照+录像的多流组合），由 Chi 层用 XML 声明；
* **Pipeline**：一条数据流图（对应 HAL3 的流集合），定义帧处理路径；
* **Node**：图上节点，映射到具体硬件块（IFE/BPS/IPE/…）或软件算法，节点间用 port + buffer manager 连接。

请求到达后由 UseCase 分发到 Pipeline，按 topology XML 描述的端口依赖逐 Node 执行。OEM 通过 Chi 的 hook（Core override / Node override / Session override）替换或插入自己的 Node/Feature。对照学习文档 ISP 篇 4.2 节的架构图答。

### Q38. MTK 的 P1 与 P2 各自负责什么？ ★★★

**P1**：对应 sensor→ISP 通路（CAM/HW 节点），承担 RAW 域处理与 3A 统计（嵌入 3A 的硬件计数器）；**P2**：通用后处理通路（MDP/WPE 等硬件节点 + SW 节点），承担 YUV 域后处理（降噪/融合/WPE 特效）。拍照流程典型是"P1 出 RAW → P2 做多帧融合与后处理 → JPEG"。追问：为什么 MTK 把多帧 HDR 放 P2（软件节点灵活、可插 OEM 算法）而高通在 IPE/Chromatix 内（固化路径效率高）。

### Q39. 48MP 传感器的 remosaic 是什么？什么时候触发？ ★★★

Quad Bayer 传感器默认 4 像素合一输出 12MP；\*\*remosaic（重排）\*\*把 2×2 同色像素重排回标准 Bayer 输出满像素 48MP。触发条件（元数据可见）：APP 请求 `supportedMaxPhotoDimensions` 的满尺寸 + HAL 判定光照充足（暗光下全像素输出信噪比崩塌，HAL 可静默降级）。框架侧 `SENSOR_PIXEL_MODE`（Android 15 标准化）声明 binning/remosaic 意图。追问：为什么 ProRAW/高质量拍照常配全像素 RAW（合并会损失高光细节与后期空间）。

### Q40. 用 perfetto 怎么拆"拍照出图慢"？ ★★★

抓取包含 camera 的 trace，按段定位：

1. **openCamera → onOpened**：provider 重绑/权限/驱逐时间；
2. **configureStreams**：HAL 配流耗时（大流组合可到数百 ms）；
3. **首帧**：`onCaptureStarted` 的第一帧（AE 冷启动收敛）；
4. **单张拍照延迟**：precapture 序列（多帧）→ STILL\_CAPTURE 请求入队 → pipeline depth 排空 → JPEG 编码 → result 回调；
5. **服务侧**：cameraserver 的 binder 事务与 CameraServiceWatchdog 异常。

对照源码链路文档第 3/4 章的请求泵与结果回程图，把每段延迟归到框架/HAL/3A 三类责任。追问：为什么"AE 收敛等待"占比最大时该优化 precapture 策略而非编码器。

> 抓取配置（Perfetto config/category）、逐段判读与归因方法见《Android\_Camera\_Trace分析文档》第 2\~4 章。

### Q41. dumpsys media.camera 的关键输出有哪些？ ★★★

* Device status：当前打开 client 的 uid/pid/package、client 状态（OPEN/ACTIVE）；
* Camera 0 的 stream 配置（各流尺寸/格式/max buffers）、in-flight 请求数与 frame number 区间；
* 请求/结果统计：帧率、丢弃计数（dropped frame/result/buffer）、错误计数；
* Vendor 扩展段（OEM 附加调试信息）。

配合 `cmd media.camera watch start -m android.control.afState,android.control.aeState`（Android 13+）实时观察 3A 状态迁移，是"3A 不收敛"类问题的第一现场工具（对照 APP 面试题 Q63 的排查路径）。

### Q42. tuning 团队要的 RAW dump / stats dump 是什么？框架怎么支持？ ★★★

RAW dump：把 ISP 输入/输出的 RAW 帧原样落盘（HAL 调试开关或 vendor tag 触发），用于离线复现 ISP 算法问题；stats dump：导出 3A 统计（直方图/AWB 白块/AF 曲线）验证算法输入。框架层配合手段：`AE_PRECAPTURE_TRIGGER` 复现序列、固定手动曝光（锁 3A 拍可控帧）、`SENSOR_TEST_PATTERN`（标准测试图排除镜头因素）。追问：dump 打开为什么影响帧率（IO 阻塞流水线，属调试态不进量产）。

### Q43. ITS（Image Test Suite）测什么？和 CTS 是什么关系？ ★★★

CTS 验证 API 行为合规（接口存在性/调用序列），ITS 验证**成像结果的正确性**：scene 类（固定图表：亮度线性、色彩准确度、边缘锐利、失真、暗部增益、时间戳抖动等几十项，需标准灯光箱）+ sensor\_fusion 类（转台验证时空对齐：旋转下多流/多摄的几何一致性）。OEM 必须全过才能出货；对系统工程师，ITS 报告是 HAL 质量的客观指标。追问：为什么 ITS 要求特定照度与图表（统计类测试的可复现前提）。

### Q44. HAL 进程卡死/camerad 无响应，定位链路是什么？ ★★★

1. logcat/watchdog：CameraServiceWatchdog 触发记录、"provider binder timeout"；
2. `debuggerd -b <provider_pid>` 拿 provider 的 tombstone/backtrace，看卡在哪个 ioctl/Node；
3. kernel 侧：CSI/CSID/IPE 的中断是否停发（`dmesg`、irq 状态）；
4. Binder dump（`service call` / binder transactions）确认是否有悬挂事务；
5. 复现时抓 perfetto 确认最后一个完成的 frame number——卡点即其后第一个请求。

自愈链：watchdog 杀 cameraserver（或 provider 重启）→ init 重拉 → 重新枚举；APP 收 onDisconnected 后重开。追问：为什么"APP 侧永久等待"是框架设计要避免的（所有等待必须有超时与错误出口）。

### Q45. 相机内存优化的方向清单？ ★★★

* **缓冲数量**：各流 max\_buffers 收敛（配合 HAL 缓冲管理 API 按需分配，Q21）；
* **分辨率**：分析流用最小可用尺寸（acquireLatestImage + 小 ImageReader）；
* **格式**：预览走 PRIV 直通（不落 YUV）；JPEG 延迟编码替代常驻 YUV 大缓冲；
* **生命周期**：Image/Bitmap 及时 close/recycle；ImageReader 随会话销毁；
* **平台手段**：gralloc 使用 flags（GPU 渲染需求才加 GPU usage）、NCB/压缩格式（设备相关）；
* **进程级**：cameraserver 缓冲池统计（dumpsys meminfo 对齐），抓取直方图确认峰值场景。

***

> 深入学习对照：Q1-Q12 ↔《源码链路文档》第 1~~2 章；Q13-Q24 ↔《源码链路文档》第 3~~4 章 + 《接口文档》第 4 章；Q25-Q36 ↔《ISP\_3A文档》第 2\~3 章；Q37-Q45 ↔《ISP\_3A文档》第 4 章 + 学习文档第 3 章。
