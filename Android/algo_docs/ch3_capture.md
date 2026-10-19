# 第 3 章 拍照算法的集成：高质量链路

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 拍照算法与预览的本质区别：**允许攒帧、允许异步、追求画质**。本章讲四条路线（HAL 多帧、ZSL reprocess、App 后处理、离线会话）的接入方式、画质杠杆与代价。

## 3.1 四条路线总览

| 路线 | 位置 | 数据域 | 画质上限 | 延迟 | 门槛 |
|---|---|---|---|---|---|
| **A. HAL/ISP 多帧融合** | vendor pipeline | RAW（HAL 内） | 最高（与系统相机一致） | 快门后几百 ms~数秒 | OEM/平台权限 |
| **B. ZSL + reprocess** | 框架会话 + HAL | YUV/PRIVATE | 高（可拿快门前的帧重处理） | 快门后 100~500ms | 设备需 reprocess 能力 |
| **C. App 侧后处理** | App（GPU/NPU） | JPEG/YUV/RAW | 取决于算法与数据 | 可异步，不阻塞预览 | 无（任何 App） |
| **D. 离线处理会话** | HAL/框架（Android 11+） | RAW/YUV | 高 | 后台完成 | 设备支持 offline processing |

选型直觉：**要"和系统相机一样好"且能改平台 → A；要"零快门延迟 + 可控重处理" → B；只要产品化效果/跨机型一致 → C；系统内部延迟优化 → D**。

## 3.2 路线 A：HAL/ISP 多帧融合（平台侧）

**它解决什么**：暗光噪声、动态范围、虚化、超分——这些都需要**多帧 RAW 融合**，而 RAW 缓冲和 ISP 带宽在 HAL 内部，App 拿不到（或拿到也来不及对齐）。

**典型实现**（B/C 层，厂商差异大）：

1. **ZSL 环形缓冲**：HAL 持续把预览帧存进环形缓冲（RAW 或内部格式），快门按下时取"按快门前后"的 N 帧；
2. **对齐**：陀螺仪/特征点估计帧间位移（手抖补偿），运动物体做局部一致性检测；
3. **融合**：时域降噪（多帧平均/加权）、HDR 包围合并、超分（多帧亚像素）、虚化（深度/双摄视差）；
4. **后处理与编码**：融合结果走 ISP 后段 + JPEG/HEIF 编码，经结果回程给 App。

**与 App 的接口**：

- 标准能力：部分特性通过 `CaptureRequest` 键暴露（如防抖、部分 HDR 模式）；
- **Camera Extensions**（Android 12+）：`EXTENSION_NIGHT`（夜景）、`EXTENSION_BOKEH`（虚化）、`EXTENSION_FACE_RETOUCH` 等，`CameraManager.getCameraExtensionCharacteristics()` 查询、`createExtensionSession()` 使用；CameraX 侧有 `camera-extensions` 封装；
- **Vendor tag**：OEM 私有开关（夜景强度、美颜等级），App 按名字查询后写入请求；
- 触发时序：通常仍是"precapture 收敛 → STILL_CAPTURE"，多帧发生在 HAL 内部，App 只见出图变慢。

**优点**：画质天花板最高、与系统相机同源、可 RAW 域处理；
**代价**：需要平台/OEM 配合；机型移植成本高；效果不可由 App 快速迭代。

## 3.3 路线 B：ZSL + reprocess（框架侧能力）

**它解决什么**：让 App 在"零快门延迟"的前提下，对**已经曝光过的帧**做高质量重处理（而不是等快门后再曝光）。

**使用方式**（A 层 API）：

1. 设备能力：`REQUEST_AVAILABLE_CAPABILITIES` 含 `YUV_REPROCESSING`（LEVEL_3）或 `PRIVATE_REPROCESSING`；
2. 建**可重处理会话**：`createReprocessableCaptureSession(InputConfiguration, outputs, callback, handler)`；
3. 持续用 `TEMPLATE_ZERO_SHUTTER_LAG` 发预览请求，拿到每帧的 `TotalCaptureResult`（含 3A 元数据）；
4. 用户按快门时，取最近的 `TotalCaptureResult` + 对应帧，构造 `createReprocessCaptureRequest(result)`，设置高质量参数（如降噪强度、后处理档位），提交 reprocess；
5. 结果经 `processCaptureResult` 返回，App 从 ImageReader 取图。

**注意点**：

- reprocess 请求的帧号/时间戳沿用原始帧，**结果元数据必须与原始捕获一致**（设置里不能改会导致物理语义变化的值）；
- 不是所有设备支持；PRIVATE reprocess 效率更高（HAL 可用内部格式），YUV reprocess 更通用；
- App 只需处理"取哪一帧、给什么后处理参数"，对齐/融合仍在 HAL 内。

## 3.4 路线 C：App 侧拍照后处理（应用集成）

**单帧后处理（最常见）**：

- 拿到 `ImageReader` 的 JPEG/HEIF 或 RAW（`RAW_SENSOR` + `DngCreator` 存 DNG）；
- 在 App 内用 GPU（GL/Vulkan）或 NPU（TFLite/NNAPI）做：调色/LUT、美颜、锐化、风格化、AI 增强、文档矫正；
- 产物质量取决于**数据域**：JPEG 已是显示域（信息有损），RAW 保留最大后期空间但需自建显影链（去马赛克/降噪/色彩，工程量参考《Android_Camera_ISP_3A文档》）。

**多帧后处理（App 自建夜景/HDR）**：

1. 用 `ImageReader` 连续捕获 N 帧（YUV 或 RAW），`maxImages` 要足够大（N 帧 × 缓冲），注意内存：12MP YUV420 单帧约 18MB，8 帧 >140MB；
2. 对齐：陀螺仪 + 特征点单应变换（或 EIS 数据），运动区域检测防鬼影；
3. 融合：加权平均/中值/曝光包围合并；
4. 后处理与编码：GPU 合成 → JPEG/HEIF 写盘；
5. 与预览解耦：捕获/合并放独立线程或独立会话，避免阻塞预览（JPEG stalling 会掉预览帧）。

**代价与风险**：内存峰值、对齐质量（手持夜景的鬼影）、热与功耗、低端机帧率；**收益**：跨机型一致、效果可快速迭代、不依赖 OEM。

## 3.5 路线 D：离线处理会话（Android 11+）

- HAL 支持 `offline_processing` 能力时，切会话/关相机可以把**已提交未完成**的请求与缓冲移交给 `ICameraOfflineSession` 继续处理；
- 典型收益：连拍/多帧拍照时，用户切走相机，剩余照片后台出图，不占用在线设备；
- App 侧无直接 API（框架/HAL 内部机制），但会体现在"出图延迟"与"相机可快速释放"上；
- 对照 iOS：deferred photo processing 是应用可见的"先出预览成品、后台替换精修"机制（见 iOS 学习文档第 4 章），Android 的离线会话更偏框架协议。

## 3.6 画质杠杆：钱花在哪最值

| 画质维度 | 最有效的层 | 说明 |
|---|---|---|
| 噪声（暗光） | HAL 多帧（RAW 域） | 时域融合 + 噪声模型，YUV 域已损失信息 |
| 动态范围 | HAL 多帧（包围曝光） | 不同曝光帧合并，需对齐与鬼影抑制 |
| 细节/锐度 | ISP/RAW 域 | 去马赛克 + 局部对比度，YUV 域只能补偿 |
| 色彩/风格 | App 后处理（成本最低） | LUT/调色对数据域不敏感，迭代快 |
| 人像虚化 | HAL（双摄深度/多帧）或 App（ML 分割） | HAL 效果更自然，App 更可控 |
| 超分 | HAL（多帧）或 App（AI） | 多帧亚像素最难但效果最好 |
| 文档矫正/去反光 | App（CV） | 与相机管道解耦，分析流即可 |

## 3.7 延迟与资源预算

- 多帧融合的延迟 ≈ 帧数 × 帧间隔 + 对齐/融合 + 编码，暗光手持夜景可到 1~3s；
- JPEG/HEIF 编码 10~100ms（分辨率/机型相关）；**不要让编码占用预览所需带宽**（stalling stream 的问题）；
- 内存峰值要按"最大并发帧数 × 单帧大小"估算，低端机优先降分辨率/帧数；
- 热：长时间连拍/夜景会触发降频，出图变慢——预算里要留余量。

## 3.8 常见坑

1. **`Image` 不 close**：缓冲耗尽，后续拍照卡死（最多人栽的坑）；
2. **`maxImages` 估算错误**：多帧 burst 时缓冲不足导致丢帧或丢图；
3. **在相机回调线程做融合**：阻塞结果回程；算法另起线程；
4. **拍照与预览共用大缓冲**：拍照瞬间预览掉帧（JPEG stalling/带宽）；
5. **方向/EXIF 处理不一致**：后处理旋转后 EXIF 未更新，缩略图方向错；
6. **RAW 与 JPEG 同时要**：流组合与带宽翻倍，需验证设备支持；
7. **忘记恢复预览**：拍照完未恢复 repeating，预览冻结。

## 3.9 本章小结

- 拍照算法四路线：HAL 多帧（画质最高）、ZSL reprocess（零延迟可控）、App 后处理（灵活）、离线会话（系统级）；
- 画质的关键在**数据域与帧数**：RAW + 多帧 > YUV 多帧 > 单帧 JPEG；
- 拍照侧的大计算必须与预览解耦（独立线程/会话），否则"拍一张卡一下"。

下一章：无论哪条路线都要面对的工程细节（数据、同步、内存、线程、热、调试）。
