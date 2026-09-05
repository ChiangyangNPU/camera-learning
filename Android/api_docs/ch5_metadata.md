# 第 5 章 元数据体系（camera_metadata）

Camera2/HAL3 的一切控制与结果传递都建立在 `camera_metadata` 之上：应用下发的是一份份元数据（CaptureRequest），HAL 返回的也是元数据（CaptureResult），设备能力描述同样是一份元数据（CameraCharacteristics）。本章基于 AOSP `system/media` 仓库的源码，梳理这套体系的 C API、tag 组织方式、条目总量统计与厂商扩展机制。

---

## 5.1 元数据体系概览

> 来源：[camera_metadata.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata.h)、[camera_metadata_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata_tags.h)、[Camera HAL3 元数据概念页（source.android.google.cn）](https://source.android.google.cn/docs/core/camera/camera3_metadata?hl=zh-cn)

### 5.1.1 为什么用元数据

Camera1 时代，应用通过 `Camera.Parameters` 以**扁平字符串协议**与 HAL 交互：`set("key=value;key2=value2")`。这种协议有明显缺陷：

- **弱类型**：所有值都是字符串，int/float/数组全靠手写序列化，解析容易出错；
- **无结构**：表达不了矩形、区域（metering region）、ration 等复合类型；
- **不可逐帧控制**：Camera1 的 parameters 是设备级状态，改一次要整包重新下发，难以做 HAL3 的"每帧一个请求"流水线；
- **扩展性差**：OEM 加自定义参数只能继续往字符串里塞 key，容易互相冲突、无版本约束。

Camera2 改用**强类型的二进制元数据缓冲区**：每个数据项由 `tag`（uint32 编号）标识，附带 `type`（6 种基本类型之一）和 `count`（元素个数），值按类型定长存放。请求、结果、设备特性全是同一种结构 `camera_metadata_t`，可整体 memcpy、逐帧下发，AOSP 官方概念页对此的概括是：静态特性通过大幅扩展的 `getCameraInfo()` 提供逐帧设置通过捕获请求传递（曝光时间、帧时长、感光度等），而结果元数据必须回报 HAL **实际生效**的值（例如应用请求帧时长 0，HAL 报告被限制后的最小值）。

### 5.1.2 缓冲区结构：camera_metadata_t

`camera_metadata.h` 中的核心注释可归纳为：

- 一份元数据是**一块连续内存**：头部 + 定长 entry 插槽数组 + 溢出数据区，字节数由 `get_camera_metadata_size()` 给出，因此可以安全地用 `memcpy()` 复制；
- 容量**固定**（entry_capacity / data_capacity），满了不会自动扩容；
- entry 默认**不排序**，且**允许同一 tag 出现多次**；`sort_camera_metadata()` 排序后可加速按 tag 查找，但再 add/append 会回到未排序状态；
- 每个条目在代码中以 `camera_metadata_entry_t`（可写引用）或 `camera_metadata_ro_entry_t`（只读引用，布局相同）暴露：

```c
typedef struct camera_metadata_entry {
    size_t   index;   // 在缓冲区中的位置
    uint32_t tag;     // tag 编号
    uint8_t  type;    // TYPE_BYTE ~ TYPE_RATIONAL
    size_t   count;   // 元素个数（不是字节数）
    union {           // 指向缓冲区内真实数据
        uint8_t *u8;  int32_t *i32;  float *f;
        int64_t *i64; double  *d;    camera_metadata_rational_t *r;
    } data;
} camera_metadata_entry_t;
```

### 5.1.3 三大类元数据

| 类别 | Java 层 | XML 中的 kind | 说明 |
|---|---|---|---|
| 静态特性 | `CameraCharacteristics` | `<static>` | 设备固有能力，**不打开设备即可查询**；`camera_metadata_tags.h` 注释说明 `*_INFO` section 专门存放这类"未开设备就能拿到"的静态信息 |
| 请求设置 | `CaptureRequest` | `<controls>` | 应用每帧下发、期望生效的控制值（曝光、对焦、裁剪等） |
| 结果 | `CaptureResult` | `<dynamic>` | HAL 每帧返回的实际生效值、状态与统计信息 |

### 5.1.4 tag 命名规则

- C 层：全大写蛇形命名 `ANDROID_<SECTION>_<NAME>`，如 `ANDROID_CONTROL_AE_MODE`；
- 字符串名：`android.<section>.<name>`，如 `android.control.aeMode`；
- Java 层 `Key.getName()` 返回的是以句点分隔的 `root.section[.subsections].name`，且标准 key 一律以 `android.` 开头，厂商 key 以 `com.` 等前缀区分；
- `camera_metadata_tags.h` 中每个 tag 枚举后都带一行机器生成注释，格式为 `// <类型> | <可见性> | <HAL 版本>`，例如：

```c
ANDROID_CONTROL_AE_MODE =             // enum         | public       | HIDL v3.2
    ANDROID_CONTROL_AE_MODE_OFF,      // HIDL v3.2
```

### 5.1.5 数据类型

`camera_metadata.h` 定义 6 种基本类型（`NUM_TYPES = 6`），并配有全局表 `camera_metadata_type_size[]`（每类型字节数）与 `camera_metadata_type_names[]`（类型名字符串）：

| 类型 | 枚举值 | C 类型 | 字节数 | 说明 |
|---|---|---|---|---|
| TYPE_BYTE | 0 | uint8_t | 1 | 也用于 boolean 和 8 位枚举 |
| TYPE_INT32 | 1 | int32_t | 4 | |
| TYPE_FLOAT | 2 | float | 4 | |
| TYPE_INT64 | 3 | int64_t | 8 | 常用于时间戳、曝光时长（ns） |
| TYPE_DOUBLE | 4 | double | 8 | 如 GPS 坐标 |
| TYPE_RATIONAL | 5 | camera_metadata_rational_t | 8 | 两个 int32：numerator / denominator |

### 5.1.6 可见性枚举

一个条目"谁能看见"由 `visibility` 属性决定。权威定义在 [`metadata_definitions.xsd`](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xsd) 中（沿源码实际取值列出，括号内为 XSD 原注释）：

| visibility | 含义 |
|---|---|
| `public` | Java 与 NDK 均 public，HAL 接口可见 |
| `java_public` | 仅 Java public SDK；不进 NDK |
| `ndk_public` | 仅 NDK public；Java 中是 @hide |
| `hidden` | Java 中 @hide；不进 NDK（旧文档常写作 "@hide"） |
| `system` | 不暴露给 Java/NDK，HAL 可见 |
| `extension` | Java @hide，但作为 public key 出现在 camera extensions 里 |
| `fwk_only` | Java @hide；不进 NDK，也**不进 HAL 接口**（纯框架内部） |
| `fwk_java_public` | Java public；不进 NDK、不进 HAL 接口 |
| `fwk_system_public` | Java system API（@SystemApi）；不进 NDK、不进 HAL 接口 |
| `fwk_public` | Java 与 NDK 均 public；不进 HAL 接口 |
| `fwk_ndk_public` | NDK public；不进 Java、不进 HAL 接口 |

注意：XML 中仍有一批**历史遗留条目未标注 visibility**（约 22 个），代码生成器将它们按 system/hidden 处理。

---

## 5.2 camera_metadata.h C API 函数表

> 来源：[camera_metadata.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata.h)

除非另有说明，返回 `int` 的函数 **0 表示成功、非 0 表示失败**；`find_camera_metadata_entry` 找不到时返回 `-ENOENT`。

### 分配、容量与复制

| 函数 | 作用 |
|---|---|
| `allocate_camera_metadata(entry_capacity, data_capacity)` | 分配新元数据：entry_capacity 为条目个数，data_capacity 为溢出数据字节数；用 `free_camera_metadata()` 释放 |
| `free_camera_metadata(metadata)` | 释放 `allocate_camera_metadata` 分配的结构 |
| `calculate_camera_metadata_size(entry_count, data_count)` | 计算容纳 entry_count 个条目、data_count 字节数据所需的缓冲区大小 |
| `calculate_camera_metadata_entry_data_size(type, data_count)` | 计算某条目需要的溢出数据字节数（小数据直接内联在 entry 里，返回 0） |
| `place_camera_metadata(dst, dst_size, ...)` | 在**已有缓冲区**头部放置元数据结构（调用方自己管理内存） |
| `copy_camera_metadata(dst, dst_size, src)` | 复制到已有缓冲区并**压实**（容量裁剪到实际用量） |
| `clone_camera_metadata(src)` | 按 src 实际用量分配最小新缓冲并复制（内部即"分配+append"），最常用 |
| `allocate_copy_camera_metadata_checked(src, src_size)` | 按给定大小分配并复制，**复制后做结构校验**，失败则返回 NULL；用于接收不可信来源的二进制元数据 |
| `get_camera_metadata_alignment()` | 返回整包元数据所需的对齐字节数 |
| `get_camera_metadata_size(m)` / `get_camera_metadata_compact_size(m)` | 总大小（含预留空间）/ 压实后大小 |
| `get_camera_metadata_entry_count(m)` / `_entry_capacity(m)` | 当前条目数 / 最大条目容量 |
| `get_camera_metadata_data_count(m)` / `_data_capacity(m)` | 已用溢出数据字节数 / 容量 |
| `validate_camera_metadata_structure(m, expected_size)` | 结构校验（防越界），返回 0 / `CAMERA_METADATA_VALIDATION_ERROR` / `CAMERA_METADATA_VALIDATION_SHIFTED`（未对齐但可被 clone 修复）；反序列化不可信数据前必调 |

### 读取、写入与遍历

| 函数 | 作用 |
|---|---|
| `add_camera_metadata_entry(dst, tag, data, data_count)` | 按当前最高 index 追加一个条目；未知 tag 或容量不足报错；vendor tag 需先设置查询回调（见 5.5） |
| `update_camera_metadata_entry(dst, index, data, data_count, updated_entry)` | 按下标更新数据；大小不变 O(1)，变长 O(N)；保持排序；会使旧的 entry.data 指针失效 |
| `find_camera_metadata_entry(src, tag, entry)` | **按 tag 查找**；同 tag 多条时不保证返回哪个；先 sort 可加速查找 |
| `find_camera_metadata_ro_entry(src, tag, entry)` | 同上，但返回只读引用 |
| `get_camera_metadata_entry(src, index, entry)` | 按**下标**取条目（可写引用） |
| `get_camera_metadata_ro_entry(src, index, entry)` | 按下标取条目（只读引用） |
| `delete_camera_metadata_entry(dst, index)` | 删除条目；需重排 entry 与数据，代价较高；保持排序 |
| `append_camera_metadata(dst, src)` | 把 src 的所有条目追加进 dst（不自动扩容）；结果变为未排序 |
| `sort_camera_metadata(dst)` | 排序以加速按 tag 查找；add/append 之后会退回未排序 |

关于"iterator"：当前 `camera_metadata.h` 的公开 API 中**没有** `get_camera_metadata_iterator` 之类的迭代器对象，遍历的标准写法是下标循环：

```c
size_t n = get_camera_metadata_entry_count(meta);
for (size_t i = 0; i < n; i++) {
    camera_metadata_ro_entry_t e;
    get_camera_metadata_ro_entry(meta, i, &e);
    // e.tag / e.type / e.count / e.data...
}
```

### tag 元信息查询

| 函数 | 作用 |
|---|---|
| `get_camera_metadata_section_name(tag)` | 返回 tag 所属 section 名（如 `"control"`）；vendor tag 需先注册查询回调，否则返回 NULL |
| `get_camera_metadata_tag_name(tag)` | 返回 tag 名（不含 section，如 `"aeMode"`） |
| `get_camera_metadata_tag_type(tag)` | 返回 tag 类型枚举，未知返回 -1 |
| `get_local_camera_metadata_section_name/_tag_name/_tag_type(tag, meta)` | 同上三个，但会**结合该缓冲区的 vendor tag 回调**解析 vendor tag |
| `camera_metadata_section_bounds[ANDROID_SECTION_COUNT][2]` | 全局表：每个 section 的 tag 起止范围 |
| `camera_metadata_section_names[ANDROID_SECTION_COUNT]` | 全局表：section 名字符串 |
| `camera_metadata_type_size[]` / `camera_metadata_type_names[]` | 类型大小 / 类型名全局表 |
| `camera_metadata_enum_snprint(tag, value, dst, size)` | 把**枚举类 tag** 的值打印成可读字符串 |
| `camera_metadata_enum_value(tag, name, size, value)` | 枚举名反查数值（与上一函数互逆） |

### 调试

| 函数 | 作用 |
|---|---|
| `dump_camera_metadata(m, fd, verbosity)` | 打印元数据；verbosity 0=仅条目，1=加 16 个数据值，2=全量 |
| `dump_indented_camera_metadata(m, fd, verbosity, indentation)` | 同上，带缩进参数（便于嵌套打印） |

### 旧版 vendor 查询（已废弃）

| 函数 / 类型 | 作用 |
|---|---|
| `vendor_tag_query_ops_t` + `set_camera_metadata_vendor_tag_ops(query_ops)` | 旧机制：向元数据库注册厂商 tag 的名字/类型查询回调；头文件标注 **DEPRECATED**，应改用 `camera_vendor_tags.h` 的 `vendor_tag_ops`（见 5.5） |

---

## 5.3 tag 组织与命名空间

> 来源：[camera_metadata_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata_tags.h)

### 5.3.1 tag 编号编码

- tag 是 32 位整数：**高 16 位 = section 编号，低 16 位 = section 内偏移**（`camera_metadata_section_start_t` 用 `section << 16` 表达起始值）；
- 主枚举 `camera_metadata_section_t` 当前有 **36 个 section**（`ANDROID_SECTION_COUNT = 36`），另设 `VENDOR_SECTION = 0x8000`，故厂商 tag 的编号全部 ≥ `0x80000000`（与 `camera_vendor_tags.h` 的 `CAMERA_METADATA_VENDOR_TAG_BOUNDARY` 一致）；
- 每个 section 枚举后有对应的 `*_START` 边界；`camera_metadata_tags.h` 的 tag 枚举中每个 section 还有 `*_END` 边界标记；
- 头文件注释强调：**新 section 只能加在 ANDROID_SECTION_COUNT 之前**，以保证既有枚举值不变（ABI 稳定）。

### 5.3.2 section 一览（按 tags.h 枚举顺序）

| Section | 用途（一句话） | 主要 tag 举例 |
|---|---|---|
| `COLOR_CORRECTION` | 3A 之后的色彩精调（色温增益、色彩矩阵、色差校正） | `mode`, `gains`, `transform`, `aberrationMode` |
| `CONTROL` | 3A 与整机拍摄控制的**核心 section** | `mode`, `aeMode`, `afMode`, `awbMode`, `aeTargetFpsRange`, `postRawSensitivityBoost` |
| `DEMOSAIC` | RAW 去马赛克（插值成 RGB） | `mode` |
| `EDGE` | 边缘增强（锐化） | `mode`, `strength`, `availableEdgeModes` |
| `FLASH` | 闪光灯控制 | `mode`, `firingPower`, `firingTime` |
| `FLASH_INFO` | 闪光灯静态能力 | `available`, `chargeDuration` |
| `HOT_PIXEL` | 热点/坏点校正 | `mode`, `availableHotPixelModes` |
| `JPEG` | JPEG 压缩参数与 EXIF/GPS 写入 | `quality`, `thumbnailSize`, `orientation`, `gpsCoordinates` |
| `LENS` | 镜头物理控制（光圈、变焦、对焦、防抖） | `aperture`, `focalLength`, `focusDistance`, `opticalStabilizationMode` |
| `LENS_INFO` | 镜头静态信息 | `facing`, `availableFocalLengths`, `poseRotation`, `intrinsicCalibration` |
| `NOISE_REDUCTION` | 降噪 | `mode`, `strength`, `availableNoiseReductionModes` |
| `QUIRKS` | HAL 实现的兼容性/特殊行为标记 | `meteringCropRegion`, `triggerAfWithAuto`, `useZslFormat` |
| `REQUEST` | 请求自身信息与输出流能力 | `frameCount`, `id`, `maxNumOutputStreams`, `availableCapabilities`；动态侧 `pipelineDepth` |
| `SCALER` | 裁剪、旋转、镜像与流配置（分辨率/格式/帧率） | `cropRegion`, `rotateAndCrop`, `availableStreamConfigurations`, `availableFormats` |
| `SENSOR` | 传感器曝光、时序与 RAW 校准 | `exposureTime`, `frameDuration`, `sensitivity`, `testPatternMode` |
| `SENSOR_INFO` | 传感器静态信息 | `activeArraySize`, `colorFilterArrangement`, `sensitivityRange`, `orientation` |
| `SHADING` | 镜头阴影（暗角）校正 | `mode`, `strength`, `availableModes` |
| `STATISTICS` | 3A 统计与人脸信息输出 | `faceDetectMode`, `lensShadingMapMode`, `hotPixelMapMode`, `faceRectangles` |
| `STATISTICS_INFO` | 统计能力静态信息 | `availableFaceDetectModes`, `maxFaceCount`, `histogramBucketCount` |
| `TONEMAP` | 色调映射（Gamma/HDR 曲线） | `mode`, `curveBlue`, `curveGreen`, `curveRed`, `gamma` |
| `LED` | 前置 LED 通知灯 | `transmit`, `availableLeds` |
| `INFO` | 设备整体等级与版本 | `supportedHardwareLevel`, `version`, `supportedBufferManagementVersion` |
| `BLACK_LEVEL` | 黑电平补偿锁定 | `lock` |
| `SYNC` | 请求与结果的帧同步 | `maxLatency`（静态），`frameNumber`（动态） |
| `REPROCESS` | 重处理（YUV/RAW 二次处理） | `effectiveExposureFactor`, `maxCaptureStall` |
| `DEPTH` | 深度数据流（DepthXXX） | `maxDepthSamples`, `availableDepthStreamConfigurations`, `availableDepthMinFrameDurations` |
| `LOGICAL_MULTI_CAMERA` | 逻辑多摄像头 | `physicalIds`, `sensorSyncType`, `activePhysicalId`, `activePhysicalSensorCropRegion` |
| `DISTORTION_CORRECTION` | 镜头畸变校正 | `mode`, `availableModes` |
| `HEIC` | HEIF 编码拍照 | `availableHeicStreamConfigurations`, `availableHeicStallDurations`, `availableHeicUltraHdrStreamConfigurations` |
| `HEIC_INFO` | HEIC 能力静态信息 | `supported`, `maxJpegAppMarkersCount` |
| `AUTOMOTIVE` | 车载摄像头 | `location`，以及（lens 子命名空间）`AUTOMOTIVE_LENS_FACING` |
| `AUTOMOTIVE_LENS` | 车载镜头朝向（HIDL v3.8 起拆分出的 section） | `facing` |
| `EXTENSION` | 相机扩展（夜视、人像等 extensions 接口） | `strength`, `currentType`, `nightModeIndicator` |
| `JPEGR` | UltraHDR（JPEG_R） | `availableJpegRStreamConfigurations`, `availableJpegRMinFrameDurations`, `availableJpegRStallDurations` |
| `SHARED_SESSION` | 共享会话配置 | `colorSpace`, `outputConfigurations`, `configuration` |
| `DESKTOP_EFFECTS` | 桌面模式背景虚化等人像效果 | `backgroundBlurMode`, `faceRetouchMode`, `faceRetouchStrength`, `capabilities` |

> 注意 `*_INFO` 拆分的来源：在 `metadata_definitions.xml` 源定义里并没有独立的 `flashInfo/lensInfo/sensorInfo/statisticsInfo/heicInfo` section，而是写在对应主 section 的 `<static>` 内嵌 `<info>` 子块中，代码生成时拆成独立 section。`AUTOMOTIVE_LENS` 同理，源自 `automotive` section 内嵌的 `<namespace name="lens">` 子块。

---

## 5.4 metadata_properties.xml 统计（现名 metadata_definitions.xml）

> 来源：[metadata_definitions.xml](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml)（main 分支上已由旧文件名 `metadata_properties.xml` 改名而来，结构不变：`namespace → section → controls/static/dynamic → entry`）

用 python（xml.etree）解析统计，**30 个 section、共 371 个 entry**（条目）。分 kind 统计如下（controls=可下发请求项，static=静态能力，dynamic=结果项；同一 tag 常同时出现在多个 kind 中，此处按声明处计数）：

| Section | controls | static | dynamic | 合计 |
|---|---|---|---|---|
| colorCorrection | 6 | 3 | 0 | 9 |
| control | 29 | 29 | 9 | 67 |
| demosaic | 1 | 0 | 0 | 1 |
| edge | 2 | 1 | 0 | 3 |
| flash | 4 | 10 | 1 | 15 |
| hotPixel | 1 | 1 | 0 | 2 |
| jpeg | 8 | 2 | 1 | 11 |
| lens | 5 | 17 | 2 | 24 |
| noiseReduction | 2 | 1 | 0 | 3 |
| quirks | 0 | 4 | 1 | 5 |
| request | 6 | 20 | 2 | 28 |
| scaler | 3 | 33 | 1 | 37 |
| sensor | 6 | 33 | 11 | 50 |
| shading | 2 | 1 | 0 | 3 |
| statistics | 6 | 9 | 20 | 35 |
| tonemap | 7 | 2 | 0 | 9 |
| led | 1 | 1 | 0 | 2 |
| info | 0 | 7 | 0 | 7 |
| blackLevel | 1 | 0 | 0 | 1 |
| sync | 0 | 1 | 1 | 2 |
| reprocess | 1 | 1 | 0 | 2 |
| depth | 0 | 15 | 0 | 15 |
| logicalMultiCamera | 0 | 2 | 2 | 4 |
| distortionCorrection | 1 | 1 | 0 | 2 |
| heic | 0 | 14 | 0 | 14 |
| automotive | 0 | 2 | 0 | 2 |
| extension | 1 | 0 | 2 | 3 |
| jpegr | 0 | 6 | 0 | 6 |
| sharedSession | 0 | 3 | 0 | 3 |
| desktopEffects | 4 | 2 | 0 | 6 |
| **合计** | **106** | **229** | **56** | **371** |

条目量最大的三个 section 是 `control`（67，3A 控制）、`sensor`（50）、`scaler`（37，流配置），符合"控制多、校准多、流配置多"的直觉。

**visibility 分布**（按 entry 元素统计）：public 189、ndk_public 72、java_public 29、system 29、hidden 18、fwk_only 7、fwk_java_public 3、fwk_public 1、fwk_system_public 1、未标注 22（生成代码按 system/hidden 处理）。据此粗估：

- Java SDK 可见（public + java_public + fwk_java_public）约 **221** 个（个别标记 synthetic/deprecated 的不会生成 Java Key）；
- NDK 可见（public + ndk_public）约 **261** 个；
- HAL 接口可见（非 fwk_* 系列）约 **359** 个；
- 与生成结果对照：`camera_metadata_tags.h` 枚举中有约 345 个具体 tag（另加 35 个 `*_END` 边界），对应 XML 中 371 − 25（synthetic="true"，不导出 C）个非合成条目。

**与 Java 框架层的映射**：应用层的 `CameraCharacteristics.Key` / `CaptureRequest.Key` / `CaptureResult.Key` 就是这些 tag 在框架层的投影。构建链路是：`metadata_definitions.xml` → `camera/docs` 下的 mako 模板（`CameraCharacteristicsKeys.mako`、`CaptureRequestKeys.mako`、`CaptureResultKeys.mako`、`CameraMetadataEnums.mako` 等）生成 Java 的 Key 常量与枚举注释，同时生成 `camera_metadata_tags.h/.c`；运行期 `CameraMetadataNative` 用 tag 的字符串名（如 `android.control.aeMode`）与 HAL 传来的二进制元数据互相查找，`Key<T>` 的泛型参数决定了值的 Java 类型（`Integer`↔TYPE_INT32、`Rect`↔int32[4]、`Range`/`Size`/`MeteringRectangle` 等按固定布局解包）。应用可参考 [CameraCharacteristics.Key 官方页](https://developer.android.google.cn/reference/android/hardware/camera2/CameraCharacteristics.Key)：`Key` 由 `new Key<>(name, type)` 构造，设备实际支持的静态 key 用 `CameraCharacteristics.getKeys()` 枚举（请求/结果侧对应 `getAvailableCaptureRequestKeys()` / `getAvailableCaptureResultKeys()`）。

> 统计脚本（可复现，文件下载后直接运行）：
>
> ```python
> import xml.etree.ElementTree as ET
> NS = '{http://schemas.android.com/service/camera/metadata/}'
> root = ET.parse('metadata_definitions.xml').getroot()
> for ns in root.findall(NS+'namespace'):
>     for sec in ns.findall(NS+'section'):
>         n = len(sec.findall('.//'+NS+'entry'))
>         print(sec.get('name'), n)
> ```

---

## 5.5 厂商自定义元数据（vendor tag）

> 来源：[camera_vendor_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_vendor_tags.h)、[ICameraProvider.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/ICameraProvider.aidl)、[CameraProviderManager.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/common/CameraProviderManager.cpp)

### 5.5.1 编号空间与命名规范

- 厂商 tag 的编号必须 ≥ `CAMERA_METADATA_VENDOR_TAG_BOUNDARY = 0x80000000`（对应 section 枚举 `VENDOR_SECTION = 0x8000`，tag 高 16 位为 0x8000 起的厂商自分区号）；
- vendor section 名必须以**厂商名的 Java 包风格前缀**开头，例如 CameraZoom Inc. 用 `com.camerazoom.`；
- 允许多个厂商 section 并存：手机厂商、芯片厂商、摄像头模组厂商可以各自维护 `com.<vendor>.` 前缀的 section。

### 5.5.2 注册与查询机制

**HAL 侧接口** `vendor_tag_ops_t`（`camera_vendor_tags.h`，共 5 个函数指针 + 8 个保留位）：

| 函数指针 | 作用 |
|---|---|
| `get_tag_count(v)` | 返回本平台支持的 vendor tag 数量（出错返回 -1） |
| `get_all_tags(v, tag_array)` | 填充所有 vendor tag 编号数组 |
| `get_section_name(v, tag)` | 返回 vendor section 名（如 `com.camerazoom.zoom`） |
| `get_tag_name(v, tag)` | 返回 tag 名 |
| `get_tag_type(v, tag)` | 返回类型（须是 `camera_metadata.h` 定义的 6 种之一）；越界返回 -1 |

**框架侧缓存接口** `vendor_tag_cache_ops`：与上面同构，但每个函数多带一个 `metadata_vendor_id_t id`（uint64_t，`CAMERA_METADATA_INVALID_VENDOR_ID = UINT64_MAX`），因为框架同时面对多个 provider，需要按 id 隔离各家的 vendor tag 表。

**打通链路**（以源码为准）：

1. HAL 在 provider 接口上声明 vendor tag：HIDL `android.hardware.camera.provider@2.4` 与 AIDL `ICameraProvider` 均提供 `getVendorTags()`，返回 `VendorTagSection[]`（每项含 tagId、tagName、tagType、tagSectionName）；HIDL 版签名 `getVendorTags() generates (Status status, vec<VendorTagSection> sections)`。
2. 框架 `CameraProviderManager` 在装载 provider 时用 `generateVendorTagId(providerName)`（对 provider 名做 `std::hash`）为它生成唯一的 `metadata_vendor_id_t`（成员 `mProviderTagid`），并把 `getVendorTags()` 的结果转换成 `VendorTagDescriptor` 存入 `ProviderInfo::mVendorTagDescriptor`；随后 `VendorTagDescriptorCache::setAsGlobalVendorTagCache()` 把"provider id → descriptor"设为全局缓存。
3. 之后框架在元数据与 HAL 之间传递时，用 `camera_metadata_t` 头部新增的 8 字节 `vendor_id` 字段标记归属（`Camera3Device` 用内部函数 `set_camera_metadata_vendor_id()` 打标），C 层的 `get_local_camera_metadata_*_vendor_id()` 系列即可按 id 解析 vendor tag 的名字/类型。
4. 一个细节澄清：很多资料提到的 `setVendorTagId` 并不是 HAL 接口里的现成 API（AIDL/HIDL 的 `ICameraDeviceSession` 上并无此方法）；源码里对应的是上述**框架侧概念**——`generateVendorTagId()` 生成的 `mProviderTagid` 与 `getProviderTagIdLocked()` 的按设备查询。HAL 侧声明的入口只有 `getVendorTags()`。

### 5.5.3 OEM 如何扩展、应用侧如何读取

- **OEM**：在 HAL 实现里定义自己的 tag 表、实现 `vendor_tag_ops_t`，通过 provider 的 `getVendorTags()` 上报；之后即可在 characteristics/request/result 元数据中自由使用这些 tag。
- **Java 应用**：vendor key **不会**出现在 `getKeys()` 之类的公开枚举里，也没有 SDK 常量；读取方式是用厂商文档中的名字自行构造 Key 再取值：
  ```java
  Key<Byte> zoomKey = new CameraCharacteristics.Key<>("com.camerazoom.zoomMode", Byte.class);
  Byte mode = characteristics.get(zoomKey);   // 失败会抛 IllegalArgumentException
  ```
  `Key.getName()` 的文档同样约定：非 `android.` 前缀、以 `com.` 开头的 name 属于设备/平台私有 key。
- **NDK**：API 24+ 提供 `ACameraManager_getVendorTags()` 枚举厂商 tag（`ACameraVendorTagsVendorTag` 结构含 id/name/sectionName/type），随后可用 `ACameraMetadata_getConstEntry()` 按 tag 读取。

---

## 5.6 查阅指南

> 来源：综合 [camera_metadata_tags.h](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/include/system/camera_metadata_tags.h)、[metadata_definitions.xml](https://android.googlesource.com/platform/system/media/+/refs/heads/main/camera/docs/metadata_definitions.xml)、[developer.android.google.cn CameraCharacteristics.Key](https://developer.android.google.cn/reference/android/hardware/camera2/CameraCharacteristics.Key)

遇到一个陌生的 `android.xxx.yyy`，按三条路径查（从快到全）：

1. **`camera_metadata_tags.h` 注释（最快）**：tag 枚举带 `// <类型> | <可见性> | <HAL版本>` 注释，同文件下半部分列出了所有枚举值（如 `ANDROID_CONTROL_AE_MODE_ON`）；配合 `get_camera_metadata_section_name/tag_name` 或 `dump_camera_metadata()` 的输出可直接定位。适合"只想知道类型、取值、可见性"。
2. **developer.android.google.cn 对应 Key 页（最适合应用开发者）**：把 snake_case 换成 camelCase（`android.scaler.cropRegion` → `SCALER_CROP_REGION`），到 `CameraCharacteristics` / `CaptureRequest` / `CaptureResult` 类页面搜索常量名；页面对每个 key 给出单位、取值范围、`CameraCharacteristics.Key`/`CaptureRequest.Key`/`CaptureResult.Key` 三种归属与 API level。
3. **`metadata_definitions.xml`（最权威、最完整）**：这是所有 `android.*` 条目的源定义，每个 `<entry>` 内含 description、units、range、details、`<enum>` 取值、hwlevel 要求（legacy/full 等）、hal_version；`@hide`/`system`/NDK-only 的条目只能在这里或生成文件里查到（官方网页不展示）。此外 `adb shell dumpsys media.camera` 的输出就是按此结构展开的静态特性。

学习建议：先把 5.1.2 的缓冲区结构和 5.2 的 find/update/clone 三件套用熟（这是 Framework 与 HAL 代码里出现频率最高的操作），再按 5.3 的 section 表建立"控制去 control、能力去 *_INFO、流配置去 scaler"的空间感，最后用 5.4 的统计脚本自己跑一遍 XML，对全体系条目规模留下量化印象。

<!-- ch5 done -->
