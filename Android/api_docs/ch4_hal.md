# 第 4 章 HAL 层接口（camera3.h 与 AIDL Camera HAL）

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 本章基于 AOSP main 分支源码整理（抓取日期 2026-09-05）：
> `hardware/libhardware/include_all/hardware/camera3.h`、`camera_common.h`，以及 `hardware/interfaces/camera/` 下的 AIDL 接口定义。
> 说明：main 分支上 `include/hardware/camera3.h` 已是指向 `include_all/hardware/camera3.h` 的符号链接，两者内容相同。

## 4.1 HAL 接口演进概览

> 来源：[camera_common.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera_common.h)、[hardware/interfaces/camera 目录](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/)

Android Camera HAL 的接口形态经历了三代演进，每一代都保持了"方法表 / 接口"的语义结构，但绑定方式与进程模型发生了根本变化：

| 阶段 | 接口形态 | 载体 | 绑定方式 | 引入版本 |
|---|---|---|---|---|
| Legacy | `camera_module_t` + `camera_device_t`（camera1/HAL1） | libhardware 动态库（`hw_get_module` dlopen） | 同进程直接函数调用 | Android 1.0 起 |
| HAL3（传统 C 接口） | `camera_module_t` + `camera3_device_t`（camera3.h） | libhardware 动态库 | 同进程直接函数调用（Android 8 起也可由 provider 包装成 HIDL 跑在独立进程） | HAL3.0 自 Android 4.2（ICS 之后）引入 |
| HIDL | `android.hardware.camera.provider@2.x` / `camera.device@3.x` | hardware/interfaces | Binderized HIDL（独立 provider 进程） | Android 8.0（O） |
| AIDL | `android.hardware.camera.provider` / `android.hardware.camera.device`（.aidl） | hardware/interfaces | AIDL Binder（独立 provider 进程） | **Android 13（Tiramisu）** |

C HAL 设备版本宏（`camera_common.h`，main 分支实际定义到 3_6）：

```c
#define CAMERA_DEVICE_API_VERSION_1_0   // DEPRECATED（HAL1）
#define CAMERA_DEVICE_API_VERSION_2_0   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_2_1   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_3_0   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_3_1   // NO LONGER SUPPORTED
#define CAMERA_DEVICE_API_VERSION_3_2   // 最低仍被支持的 HAL3 版本
#define CAMERA_DEVICE_API_VERSION_3_3
#define CAMERA_DEVICE_API_VERSION_3_4
#define CAMERA_DEVICE_API_VERSION_3_5
#define CAMERA_DEVICE_API_VERSION_3_6
#define CAMERA_DEVICE_API_VERSION_CURRENT  CAMERA_DEVICE_API_VERSION_3_5
// 模块版本：CAMERA_MODULE_API_VERSION_1_0 ~ 2_5，CURRENT = 2_5
```

版本与 Android 发行版的大致对应（学习参考，非严格规范）：

| C HAL 设备版本 | HIDL camera.device | HIDL camera.provider | Android |
|---|---|---|---|
| 3.2 | @3.2 | @2.4 | 8.0（O） |
| 3.3 | @3.3 | @2.4 | 8.1（O MR1） |
| 3.4 | @3.4 | @2.4 | 9（P） |
| 3.5 | @3.5 | @2.5 | 10（Q） |
| 3.6 | @3.6 | @2.6 | 11（R） |
| 3.6（C 侧无 3_7 宏） | @3.7 | @2.7 | 13（T，与 AIDL 同期） |
| —（被取代） | —（AIDL 接管） | `android.hardware.camera.provider`（AIDL） | **13（T）起新设备使用** |

演进要点：

1. **进程隔离**：HIDL/AIDL 时代，Camera HAL 运行在独立的 `camera.provider` 进程中，与 `cameraserver` 通过 Binder 通信，HAL 崩溃不再拖垮相机服务；C 接口时代 HAL 与 framework 同进程（legacy 直通）。
2. **发现模型变化**：C 接口由 `camera_module_t.get_number_of_cameras()` 静态枚举（内置摄像头 0..N-1）；provider 模型由 `ICameraProvider.getCameraIdList()` + `ICameraProviderCallback.cameraDeviceStatusChange()` 动态上报（支持 USB/外接摄像头热插拔）。
3. **能力演进**：HAL3.3 引入 reprocess 与 RAW 修正；3.4 引入部分结果/物理摄像头元数据完善与 output buffer 错误码；3.5 引入 `session_parameters`、物理摄像头设置、`is_reconfiguration_required` 前身（模块级查询）；3.6 引入 `signal_stream_flush`、`is_reconfiguration_required`、HAL buffer manager（`requestStreamBuffers/returnStreamBuffers`）。
4. **AIDL 取代 HIDL**：Android 13 起新设备要求实现 AIDL 接口；AIDL 版本化更轻量（无需 `.hal` 嵌套 import），支持 `ServiceSpecificException` 错误模型与可扩展枚举，框架（`cameraserver`/`Camera2Client`）对两种后端做了统一封装（`CameraProviderManager` 同时管理 HIDL 与 AIDL provider）。

`hardware/interfaces/camera/` 目录结构（main 分支）：

```
camera/
├── common/    # 公共类型（CameraDeviceStatus、TorchModeStatus、VendorTagSection、CameraResourceCost 等）
├── device/    # 设备接口：1.0、3.2~3.7（HIDL）与 aidl/（ICameraDevice*、Stream、CaptureRequest 等约 34 个文件）
├── metadata/  # android.hardware.camera.metadata（元数据 tag 枚举定义）
└── provider/  # provider 接口：2.4~2.7（HIDL）与 aidl/（ICameraProvider、ICameraProviderCallback）
```

## 4.2 camera_common.h 关键定义

> 来源：[camera_common.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera_common.h)

### 4.2.1 camera_module_t（模块方法表）

`camera_module_t` 是传统 C HAL 的模块入口。HAL so 导出 `HAL_MODULE_INFO_SYM`，framework 通过 `hw_get_module()` 加载后把 `hw_module_t` 强转为 `camera_module_t` 使用。

| 成员 | 方向 | 作用 | 引入的 module 版本 |
|---|---|---|---|
| `hw_module_t common` | — | 通用模块头，`common.methods->open` 即 **open()**（打开指定 camera id 的设备，返回 `hw_device_t*`，可强转为 `camera_device_t`）；返回值：0 / `-ENODEV` / `-EINVAL` / `-EBUSY`（已打开）/ `-EUSERS`（并发数上限） | 1.0 |
| `int (*get_number_of_cameras)(void)` | FW→HAL | 返回可访问的摄像头数量（id 为 0..N-1 的字符串）。module 2.4+ 只统计内置（BACK/FRONT）摄像头，外接摄像头通过状态回调上报 | 1.0 |
| `int (*get_camera_info)(int camera_id, struct camera_info *info)` | FW→HAL | 返回某摄像头的静态信息（`camera_info_t`）。2.4+ 对已断开设备应返回 `-EINVAL` | 1.0 |
| `int (*set_callbacks)(const camera_module_callbacks_t *callbacks)` | FW→HAL | 注册模块级异步回调（设备热插拔、torch 状态） | 2.1 |
| `void (*get_vendor_tag_ops)(vendor_tag_ops_t *ops)` | FW→HAL | 查询 vendor 自定义 metadata tag 的查询方法表 | 2.2 |
| `int (*open_legacy)(hw_module_t*, const char* id, uint32_t halVersion, hw_device_t**)` | FW→HAL | 以指定旧版本 HAL API 打开设备（如同一 id 同时支持 HAL1/HAL3），可选，不支持返回 `-ENOSYS` | 2.3 |
| `int (*set_torch_mode)(const char* camera_id, bool enabled)` | FW→HAL | 开关手电筒；成功后必须通过 `torch_mode_status_change()` 上报新状态 | 2.4 |
| `int (*init)(void)` | FW→HAL | HAL so 加载后、其他方法调用前的一次性初始化，可为 NULL | 2.4 |
| `int (*get_physical_camera_info)(int physical_camera_id, camera_metadata_t **static_metadata)` | FW→HAL | 获取逻辑多摄背后"未独立暴露"的物理摄像头的静态 metadata | 2.5 |
| `int (*is_stream_combination_supported)(int camera_id, const camera_stream_combination_t *streams)` | FW→HAL | 模块级流组合查询（不打开设备即可询问某组流是否支持） | 2.5 |
| `void (*notify_device_state_change)(uint64_t deviceState)` | FW→HAL | 通知整机物理状态变化（64 位掩码：低 32 位系统定义，高 32 位 vendor 定义）；在调用任何 `ICameraDevice::open` 之前至少被调用一次 | 2.5（配合 AIDL 时代语义） |
| `void* reserved[2]` | — | 保留 | — |

### 4.2.2 camera_info_t

```c
typedef struct camera_info {
    int facing;              // 朝向：CAMERA_FACING_BACK / FRONT /（2.4+）EXTERNAL
    int orientation;         // 图像需顺时针旋转的角度：0/90/180/270
    uint32_t device_version; // 即 camera_device_t.common.version（CAMERA_DEVICE_API_VERSION_3_x）
    const camera_metadata_t *static_camera_characteristics; // 静态特征 metadata（只读，模块生命周期内有效）
    int resource_cost;       // 2.4+：资源开销 [0,100]，多摄并发仲裁依据
    char** conflicting_devices;   // 2.4+：不能同时打开的冲突设备 id 列表
    size_t conflicting_devices_length;
} camera_info_t;
```

### 4.2.3 状态枚举

`camera_device_status_t`（通过 `camera_device_status_change` 上报；框架默认所有设备 PRESENT）：

| 枚举值 | 含义 |
|---|---|
| `CAMERA_DEVICE_STATUS_NOT_PRESENT = 0` | 设备未连接，open 会失败 |
| `CAMERA_DEVICE_STATUS_PRESENT = 1` | 已连接，可打开 |
| `CAMERA_DEVICE_STATUS_ENUMERATING = 2` | 已连接但正在枚举，open 返回 `-EBUSY` |

合法迁移：PRESENT↔NOT_PRESENT、NOT_PRESENT→ENUMERATING→PRESENT/NOT_PRESENT。

`torch_mode_status_t`（通过 `torch_mode_status_change` 上报；仅对 PRESENT 设备有意义）：

| 枚举值 | 含义 |
|---|---|
| `TORCH_MODE_STATUS_NOT_AVAILABLE = 0` | 闪光灯不可用（如 open() 占用），不能 set_torch_mode |
| `TORCH_MODE_STATUS_AVAILABLE_OFF = 1` | 手电筒关闭且可用 |
| `TORCH_MODE_STATUS_AVAILABLE_ON = 2` | 手电筒已开启 |

`device_state_t`（配合 `notify_device_state_change`）：`NORMAL=0`、`BACK_COVERED=1<<0`、`FRONT_COVERED=1<<1`、`FOLDED=1<<2`、`VENDOR_STATE_START=1LL<<32`。

### 4.2.4 camera_module_callbacks_t（HAL→框架回调）

| 回调 | module 版本 | 作用 |
|---|---|---|
| `void (*camera_device_status_change)(..., int camera_id, int new_status)` | 2.1 | 设备连接状态变化（外接摄像头插拔） |
| `void (*torch_mode_status_change)(..., const char* camera_id, int new_status)` | 2.4 | 手电筒可用状态变化 |

另有两个配套结构（2.5 引入，供 `is_stream_combination_supported` 使用）：`camera_stream_t`（简版流描述，字段与 camera3_stream_t 同名：`stream_type/width/height/format/usage/data_space/rotation/physical_camera_id`）与 `camera_stream_combination_t`（`num_streams/streams/operation_mode`）。

## 4.3 camera3.h 核心结构体与方法表

> 来源：[camera3.h](https://android.googlesource.com/platform/hardware/libhardware/+/refs/heads/main/include_all/hardware/camera3.h)

### 4.3.1 camera3_device_t

```c
typedef struct camera3_device {
    hw_device_t common;          // common.version 必须为 CAMERA_DEVICE_API_VERSION_3_x
    camera3_device_ops_t *ops;   // 设备方法表
} camera3_device_t;
```

framework 的 `open()` 拿到 `hw_device_t*` 后按 `common.version` 判断为 HAL3 设备，再通过 `ops` 调用。设备生命周期：`open → initialize → configure_streams → (construct_default_request_settings / process_capture_request ⇄ process_capture_result / notify) → flush → configure_streams… → close`。

### 4.3.2 camera3_device_ops_t（全部 op，方向均为 FW→HAL）

| 方法 | 引入版本 | 作用 |
|---|---|---|
| `int (*initialize)(const camera3_device*, const camera3_callback_ops_t *callback_ops)` | 3.0 | open 后首先调用一次，把回调函数表交给 HAL。非阻塞，应在 5ms 内返回（上限 10ms） |
| `int (*configure_streams)(const camera3_device*, camera3_stream_configuration_t *stream_list)` | 3.0 | 重置管线并配置新的输入/输出流。HAL 通过回写每个 `camera3_stream_t` 的 `usage/max_buffers` 等表达诉求。重负载调用（可到几百 ms） |
| `int (*register_stream_buffers)(const camera3_device*, const camera3_stream_buffer_set_t*)` | 3.0，**3.2 起废弃（必须置 NULL）** | 早期版本为某流预注册 gralloc buffer；3.2+ buffer 直接随 request 传入 |
| `const camera_metadata_t* (*construct_default_request_settings)(const camera3_device*, int type)` | 3.0 | 按 `CAMERA3_TEMPLATE_*` 用途生成默认请求设置（如 PREVIEW/STILL_CAPTURE）。非阻塞，应 1ms、上限 5ms |
| `int (*process_capture_request)(const camera3_device*, camera3_capture_request_t *request)` | 3.0 | 提交一次拍摄/重处理请求（核心路径）。HAL 异步通过 `process_capture_result()` 与 `notify()` 返回结果；提交出错时 buffer fence 归框架所有 |
| `void (*get_metadata_vendor_tag_ops)(const camera3_device*, vendor_tag_ops_t*)` | 3.0 | 填充设备级 vendor tag 查询方法 |
| `void (*dump)(const camera3_device*, int fd)` | 3.0 | dumpsys/bugreport 时输出调试文本（仅 ASCII） |
| `int (*flush)(const camera3_device*)` | 3.1 | 丢弃所有进行中的请求与 buffer，返回后设备静止，可安全 configure_streams 或提交新请求 |
| `void (*signal_stream_flush)(const camera3_device*, uint32_t num_streams, const uint32_t *stream_ids)` | 3.6 | 框架即将进入 `streamUseCases`/离线切换等"drain"流程时，通知 HAL 这些流不再有新 request，HAL 应尽快归还全部该流 buffer（必须非阻塞） |
| `int (*is_reconfiguration_required)(const camera3_device*, const camera_metadata_t *old_session_params, const camera_metadata_t *new_session_params)` | 3.6 | 会话参数变化时询问 HAL 是否需要完整重配置：返回 0 需要重配置，`-EINVAL` 可跳过 |
| `void* reserved[6]` | — | 保留 |

> 注：HAL3 方法表中不存在 `get_metadata_value`、`set_request_queue_size_fn` 这类 op（后者属于已废弃的 camera2 接口），实际 op 以上表 10 个为准。

### 4.3.3 camera3_callback_ops_t（HAL→FW 回调）

| 回调 | 引入版本 | 作用 |
|---|---|---|
| `void (*process_capture_result)(const camera3_callback_ops*, const camera3_capture_result_t *result)` | 3.0 | 返回一个请求的结果（metadata、output buffer、input buffer）。3.2 起允许同一 frame 分多次返回（partial result），同流 buffer 必须按 FIFO 顺序返回 |
| `void (*notify)(const camera3_callback_ops*, const camera3_notify_msg_t *msg)` | 3.0 | 异步事件：快门（SHUTTER）与错误（ERROR_DEVICE/REQUEST/RESULT/BUFFER）。SHUTTER 应尽早发出，buffer 要等 SHUTTER 时间戳才会派发给应用 |
| `camera3_buffer_request_status_t (*request_stream_buffers)(const camera3_callback_ops*, uint32_t num_buffer_reqs, const camera3_buffer_request_t*, uint32_t *num_stream_buffers_ret, camera3_stream_buffer_ret_t*)` | 3.6 | HAL buffer manager 模式下，HAL 主动向框架请求输出 buffer（同步阻塞调用） |
| `void (*return_stream_buffers)(const camera3_callback_ops*, uint32_t num_buffers, const camera3_stream_buffer_t*)` | 3.6 | HAL buffer manager 模式下，把不再使用的 buffer 归还框架 |

`camera3_notify_msg_t` 消息类型（`camera3_msg_type_t`）：`CAMERA3_MSG_ERROR=1`、`CAMERA3_MSG_SHUTTER=2`；错误码（`camera3_error_msg_code_t`）：`CAMERA3_MSG_ERROR_DEVICE=1`（设备级致命错误，之后只能 close）、`CAMERA3_MSG_ERROR_REQUEST=2`（整帧失败）、`CAMERA3_MSG_ERROR_RESULT=3`（metadata 生成失败）、`CAMERA3_MSG_ERROR_BUFFER=4`（单个 buffer 失败，ISM 处理流不能处理）。

请求模板 `camera3_request_template_t`：`CAMERA3_TEMPLATE_PREVIEW=1`、`STILL_CAPTURE=2`、`VIDEO_RECORD=3`、`VIDEO_SNAPSHOT=4`、`ZERO_SHUTTER_LAG=5`、`MANUAL=6`（vendor 可定义 `CAMERA3_VENDOR_TEMPLATE_*`，从 `CAMERA3_TEMPLATE_COUNT=7` 起）。

### 4.3.4 camera3_stream_t 关键字段

流（stream）是 framework 与 HAL 之间按固定格式/分辨率循环传递 buffer 的通道。字段按写入者分三类：

| 字段 | 设置者 | 说明 |
|---|---|---|
| `int stream_type` | FW | `CAMERA3_STREAM_OUTPUT=0` / `INPUT=1` / `BIDIRECTIONAL=2` |
| `uint32_t width, height` | FW | buffer 尺寸（像素） |
| `int format` | FW | `HAL_PIXEL_FORMAT_*`（graphics.h）；`IMPLEMENTATION_DEFINED` 时由 gralloc 依据 usage 决定 |
| `uint32_t usage` | **HAL** | gralloc usage；输出流为生产者 usage、输入流为消费者 usage；3.1+ 入参含消费者 usage 供 HAL 参考，HAL 必须覆写 |
| `uint32_t max_buffers` | **HAL** | 该流同时最多可被 HAL dequeue 的 buffer 数 |
| `void *priv` | **HAL** | HAL 私有句柄，框架不解析 |
| `android_dataspace_t data_space` | FW | buffer 内容语义（色彩空间/深度数据）；3.3 起有效，3.4 起用 V0 定义 |
| `int rotation` | FW | 输出旋转 `CAMERA3_STREAM_ROTATION_0/90/180/270`；3.3 起有效；HAL 必须至少支持 ROTATION_0 |
| `const char* physical_camera_id` | FW | 物理输出流所属物理摄像头 id；3.5 起有效，逻辑多摄用 |

### 4.3.5 camera3_stream_configuration_t

| 字段 | 版本 | 说明 |
|---|---|---|
| `uint32_t num_streams` | 3.0 | 流总数（含输入），至少 1 且至少 1 个输出流；最多 1 个输入流 |
| `camera3_stream_t **streams` | 3.0 | 流指针数组 |
| `uint32_t operation_mode` | 3.3 | `CAMERA3_STREAM_CONFIGURATION_NORMAL_MODE=0` / `CONSTRAINED_HIGH_SPEED_MODE=1`（慢动作）及 vendor 模式 |
| `const camera_metadata_t *session_parameters` | 3.5 | 会话参数（`ANDROID_REQUEST_AVAILABLE_SESSION_KEYS`）初值，可与 configure 一并下发 |

### 4.3.6 camera3_capture_request_t / camera3_capture_result_t

请求（FW→HAL）：

| 字段 | 版本 | 说明 |
|---|---|---|
| `uint32_t frame_number` | 3.0 | 框架递增的帧号，结果与 notify 以此对应 |
| `const camera_metadata_t *settings` | 3.0 | 本次拍摄参数；NULL 表示沿用最近一次请求（configure 后第一笔不得为 NULL） |
| `camera3_stream_buffer_t *input_buffer` | 3.0 | 输入 buffer（NULL=正常拍摄，非 NULL=reprocess） |
| `uint32_t num_output_buffers` | 3.0 | 输出 buffer 数（≥1） |
| `const camera3_stream_buffer_t *output_buffers` | 3.0 | 输出 buffer 数组 |
| `uint32_t num_physcam_settings; const char **physcam_id; const camera_metadata_t **physcam_settings` | 3.5 | 逻辑多摄逐物理摄像头的请求设置 |

结果（HAL→FW）：

| 字段 | 版本 | 说明 |
|---|---|---|
| `uint32_t frame_number` | 3.0 | 对应请求帧号 |
| `const camera_metadata_t *result` | 3.0 | 结果 metadata（同一 frame 仅一次携带；错误时返回空 buffer 并 notify ERROR_RESULT） |
| `uint32_t num_output_buffers; const camera3_stream_buffer_t *output_buffers` | 3.0 | 返回的输出 buffer（可先于全部填充完成返回，靠 release_fence 同步；3.2 起可先于 SHUTTER 返回 buffer） |
| `const camera3_stream_buffer_t *input_buffer` | 3.2 | 归还的输入 buffer |
| `uint32_t partial_result` | 3.2 | 部分结果序号，1..`android.request.partialResultCount`；仅含 buffer 时为 0 |
| `uint32_t num_physcam_metadata; const char **physcam_ids; const camera_metadata_t **physcam_metadata` | 3.5 | 逐物理摄像头结果 metadata |

### 4.3.7 camera3_stream_buffer_t

| 字段 | 说明 |
|---|---|
| `camera3_stream_t *stream` | 所属流 |
| `buffer_handle_t *buffer` | gralloc buffer 句柄 |
| `int status` | `CAMERA3_BUFFER_STATUS_OK=0` / `ERROR=1`（填充失败时置 ERROR 返回） |
| `int acquire_fence` | HAL 读写前必须等待的 sync fence fd（-1 表示无需等待）；返回结果时必须置 -1 |
| `int release_fence` | HAL 归还 buffer 时设置的 sync fence（-1 表示已就绪）；框架等待后方可复用 |

### 4.3.8 时序小结

```
framework                                HAL
   | open(id)  (hw_module_t.common.methods->open)
   | initialize(callback_ops)               ← 传入回调表
   | construct_default_request_settings(TEMPLATE_*)
   | configure_streams(stream_list)         ← HAL 回写 usage/max_buffers
   | process_capture_request(req) ────────► │ 3A/ISP 拍摄
   | ◄──────── notify(SHUTTER)              │
   | ◄──────── process_capture_result(...)  │ （可多次、partial）
   | flush()（丢帧、回收 buffer）
   | close()  (hw_device_t.common.close)
```

## 4.4 AIDL 接口

> 来源：[camera/device/aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/)、[camera/provider/aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/)（Android 13 引入；所有接口均 `@VintfStability`）

服务名格式：`android.hardware.camera.provider.ICameraProvider/<type>/<instance>`（如 `internal/0`）；设备名格式 `device@<major>.<minor>/<type>/<id>`。错误统一通过 `ServiceSpecificException` 抛出（`ILLEGAL_ARGUMENT`、`INTERNAL_ERROR`、`CAMERA_IN_USE`、`MAX_CAMERAS_IN_USE`、`CAMERA_DISCONNECTED`、`OPERATION_NOT_SUPPORTED` 等）。

### 4.4.1 ICameraProvider

> 来源：[ICameraProvider.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/ICameraProvider.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `void setCallback(ICameraProviderCallback callback)` | FW→HAL | 相机服务启动时注册回调（服务重启后需再次调用） |
| `VendorTagSection[] getVendorTags()` | FW→HAL | 返回该 provider 覆盖设备支持的 vendor tag 分组 |
| `String[] getCameraIdList()` | FW→HAL | 返回内置（BACK/FRONT）摄像头设备名列表；外接设备只经状态回调上报 |
| `ICameraDevice getCameraDeviceInterface(String cameraDeviceName)` | FW→HAL | 由设备名取 `ICameraDevice` 接口（不上电）；等价于旧模型"找到设备但未 open" |
| `void notifyDeviceStateChange(long deviceState)` | FW→HAL | 整机状态（折叠/遮盖）变化通知；首次 open 前至少调用一次 |
| `ConcurrentCameraIdCombination[] getConcurrentCameraIds()` | FW→HAL | 可同时并发出流的摄像头 id 组合（满足最小分辨率保证） |
| `boolean isConcurrentStreamCombinationSupported(in CameraIdAndStreamCombination[] configs)` | FW→HAL | 查询多摄并发流组合是否支持 |

常量：`DEVICE_STATE_NORMAL/BACK_COVERED/FRONT_COVERED/FOLDED`（与 C 接口 `device_state_t` 一致）。

### 4.4.2 ICameraProviderCallback

> 来源：[ICameraProviderCallback.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/provider/aidl/android/hardware/camera/provider/ICameraProviderCallback.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `void cameraDeviceStatusChange(String cameraDeviceName, CameraDeviceStatus newStatus)` | HAL→FW | 设备连接状态变化（对应 C 接口 `camera_device_status_change`，枚举为 `CameraDeviceStatus.PRESENT/NOT_PRESENT/ENUMERATING`） |
| `void torchModeStatusChange(String cameraDeviceName, TorchModeStatus newStatus)` | HAL→FW | 手电筒状态变化（对应 `torch_mode_status_change`） |
| `void physicalCameraDeviceStatusChange(String cameraDeviceName, String physicalCameraDeviceName, CameraDeviceStatus newStatus)` | HAL→FW | 逻辑多摄背后物理设备的状态变化（C 接口无对应物，HIDL 2.5 起新增） |

### 4.4.3 ICameraDevice

> 来源：[ICameraDevice.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraDevice.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `CameraMetadata getCameraCharacteristics()` | FW→HAL | 静态特征 metadata（对应旧 `get_camera_info` 的 `static_camera_characteristics`） |
| `CameraMetadata getPhysicalCameraCharacteristics(in String physicalCameraId)` | FW→HAL | 逻辑多摄中某物理摄像头的静态特征 |
| `CameraResourceCost getResourceCost()` | FW→HAL | 资源开销（对应 `camera_info_t.resource_cost` + 冲突设备） |
| `boolean isStreamCombinationSupported(in StreamConfiguration streams)` | FW→HAL | 流组合查询（对应 module 2.5 的 `is_stream_combination_supported`，此处下沉到设备级） |
| `ICameraDeviceSession open(in ICameraDeviceCallback callback)` | FW→HAL | 上电并打开设备，返回会话接口。错误：`CAMERA_IN_USE`（重复打开）、`MAX_CAMERAS_IN_USE`、`CAMERA_DISCONNECTED` 等 |
| `ICameraInjectionSession openInjectionSession(in ICameraDeviceCallback callback)` | FW→HAL | 打开"注入会话"（外部图像注入到 HAL，测试/虚拟相机用），不支持时 `OPERATION_NOT_SUPPORTED` |
| `void setTorchMode(boolean on)` | FW→HAL | 手电筒开关（对应 `camera_module_t.set_torch_mode`，此处位于设备级） |
| `void turnOnTorchWithStrengthLevel(int torchStrength)` | FW→HAL | 以指定亮度档位开手电筒（AIDL 新增能力，`FLASH_INFO_STRENGTH_MAXIMUM_LEVEL` 为上限） |
| `int getTorchStrengthLevel()` | FW→HAL | 查询当前亮度档位（AIDL 新增） |
| `CameraMetadata constructDefaultRequestSettings(in RequestTemplate type)` | FW→HAL | 与 Session 同名方法语义一致，但**可在 open 之前调用**（AIDL 新增） |
| `boolean isStreamCombinationWithSettingsSupported(in StreamConfiguration streams)` | FW→HAL | 带会话参数/request key 的流组合查询（AIDL 新增，服务 3.0 查询版本校验） |
| `CameraMetadata getSessionCharacteristics(in StreamConfiguration sessionConfig)` | FW→HAL | 返回随会话配置而变的特征（如 `ANDROID_CONTROL_ZOOM_RATIO_RANGE`，Android 15 起） |

> 注：需求清单中提到的 `ICameraDeviceInjectionCallback.aidl` 在 `hardware/interfaces` 中并不存在；注入相关接口为 `ICameraDevice.openInjectionSession()` 返回的 `ICameraInjectionSession`（方法 `configureInjectionStreams(in StreamConfiguration, in CameraMetadata)`，语义同 configureStreams 但不覆盖内部相机配置），以及 HIDL `device@3.7` 中的同名接口。

### 4.4.4 ICameraDeviceSession

> 来源：[ICameraDeviceSession.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraDeviceSession.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `HalStream[] configureStreams(in StreamConfiguration requestedConfiguration)` | FW→HAL | 配置流；返回 HAL 期望的每流参数（`overrideFormat/producerUsage/consumerUsage/maxBuffers/overrideDataSpace/supportOffline/enableHalBufferManager`）。对应 C 接口 `configure_streams`（`camera3_stream_configuration_t` ↔ `StreamConfiguration`：`streams/sessionParams/streamConfigCounter`） |
| `CameraMetadata constructDefaultRequestSettings(in RequestTemplate type)` | FW→HAL | 默认请求模板（对应 `construct_default_request_settings`） |
| `int processCaptureRequest(in CaptureRequest[] requests, in BufferCache[] cachesToRemove)` | FW→HAL | 提交请求（**批量**：一次可带多个请求；`cachesToRemove` 清理 HAL 侧 buffer 缓存）。对应 `process_capture_request` |
| `void flush()` | FW→HAL | 丢弃全部进行中请求并归还 buffer（对应 `flush`） |
| `void close()` | FW→HAL | 关闭设备，之后所有调用抛 `INTERNAL_ERROR`（对应 `hw_device_t.common.close`） |
| `MQDescriptor<byte, SynchronizedReadWrite> getCaptureRequestMetadataQueue()` | FW→HAL | 获取请求 metadata 快速通道 FMQ（大 metadata 走共享内存，Binder 只传帧号） |
| `MQDescriptor<byte, SynchronizedReadWrite> getCaptureResultMetadataQueue()` | FW→HAL | 结果 metadata FMQ（同上） |
| `boolean isReconfigurationRequired(in CameraMetadata oldSessionParams, in CameraMetadata newSessionParams)` | FW→HAL | 会话参数变化是否需要完整重配置（对应 3.6 `is_reconfiguration_required`；返回 true 需要重配置） |
| `oneway void signalStreamFlush(in int[] streamIds, in int streamConfigCounter)` | FW→HAL | 通知 HAL 指定流即将不再有新请求、应归还 buffer（对应 3.6 `signal_stream_flush`；AIDL 中显式 `oneway` 非阻塞） |
| `ICameraOfflineSession switchToOffline(in int[] streamsToKeep, out CameraOfflineSessionInfo offlineSessionInfo)` | FW→HAL | 把未完成请求移交离线会话继续处理（离线模式，不占用传感器/相机资源），AIDL/HIDL 3.7 新增 |
| `void repeatingRequestEnd(in int frameNumber, in int[] streamIds)` | FW→HAL | 通知 HAL 某 repeating 请求在给定流上的最后一个 frame 号，便于 HAL 判定周期结束 |
| `ConfigureStreamsRet configureStreamsV2(in StreamConfiguration requestedConfiguration)` | FW→HAL | configureStreams 的新版本：返回 `ConfigureStreamsRet{HalStream[] halStreams, boolean enableHalBufferManager}`；仅当静态元数据 `ANDROID_INFO_SUPPORTED_BUFFER_MANAGEMENT_VERSION == SESSION_CONFIGURABLE` 时调用，与 configureStreams 互斥 |

### 4.4.5 ICameraDeviceCallback

> 来源：[ICameraDeviceCallback.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraDeviceCallback.aidl)

| 方法 | 方向 | 作用 |
|---|---|---|
| `void notify(in NotifyMsg[] msgs)` | HAL→FW | 异步通知（**批量**）：`NotifyMsg` 为 union `{ ShutterMsg shutter; ErrorMsg error; }`，对应 C 接口 `notify`（`CAMERA3_MSG_SHUTTER/ERROR`） |
| `void processCaptureResult(in CaptureResult[] results)` | HAL→FW | 返回结果（**批量**、可分次 partial）：`CaptureResult{frameNumber, result, outputBuffers, inputBuffer, partialResult, physicalCameraMetadata}`，对应 `process_capture_result` |
| `BufferRequestStatus requestStreamBuffers(in BufferRequest[] bufReqs, out StreamBufferRet[] buffers)` | HAL→FW | HAL buffer manager 模式下同步请求输出 buffer（返回 `OK/FAILED_PARTIAL/FAILED_CONFIGURING/FAILED_UNKNOWN`），对应 3.6 `request_stream_buffers` |
| `void returnStreamBuffers(in StreamBuffer[] buffers)` | HAL→FW | 归还输出 buffer，对应 3.6 `return_stream_buffers` |

`StreamBuffer`（对应 C `camera3_stream_buffer_t`）：`int streamId`、`long bufferId`（buffer 缓存句柄）、`NativeHandle buffer`、`BufferStatus status`、`NativeHandle acquireFence`、`NativeHandle releaseFence`——fence 由 fd 句柄传递，取代 C 接口的裸 fence fd。

### 4.4.6 ICameraOfflineSession（补充）

> 来源：[ICameraOfflineSession.aidl](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/camera/device/aidl/android/hardware/camera/device/ICameraOfflineSession.aidl)

由 `ICameraDeviceSession.switchToOffline()` 返回：`void close()`、`MQDescriptor<...> getCaptureResultMetadataQueue()`、`void setCallback(in ICameraDeviceCallback cb)`；结果仍通过 `ICameraDeviceCallback` 回传。HAL 必须在 switch 时拍完所需传感器帧，离线后不得再访问传感器。

### 4.4.7 AIDL 与 HIDL 的主要差异

| 维度 | HIDL（provider@2.x / device@3.x） | AIDL |
|---|---|---|
| 错误模型 | 每方法返回 `Status` + 可选结果 | `void`/直接返回值 + `ServiceSpecificException` |
| 方法扩展 | 只能通过新版本号（`configureStreams_3_5` 等带后缀方法） | 接口小版本演进，新增独立方法名（如 `configureStreamsV2`、`getSessionCharacteristics`） |
| 请求提交 | `processCaptureRequest(in CaptureRequest, in BufferCache[])`（3.x 亦为批量） | `int processCaptureRequest(in CaptureRequest[], in BufferCache[])`，显式批量并返回触发帧号（HAL 可据此提前返回错误） |
| 新增能力 | 3.7：`signalStreamFlush`、`isReconfigurationRequired`、switchToOffline | 保留全部 3.7 能力；另增 `turnOnTorchWithStrengthLevel`/`getTorchStrengthLevel`（闪光灯亮度）、`constructDefaultRequestSettings`（open 前可调用）、`isStreamCombinationWithSettingsSupported`、`getSessionCharacteristics`、`repeatingRequestEnd`、`configureStreamsV2`（buffer manager 会话级配置） |
| 枚举 | `types.hal` 固定枚举 | 独立 `.aidl` 枚举/parcelable（`StreamType`、`StreamRotation`、`RequestTemplate`、`ErrorCode` 等），可扩展 |
| 流定义 | `Stream`（HIDL 3.5+ 含 `sensorPixelModesUsed`、`dynamicRangeProfile`） | `Stream` 增加 `groupId`、`bufferSize`、`useCase`、`colorSpace`、`sensorPixelModesUsed`、`dynamicRangeProfile` |
| 命名风格 | 蛇形（`process_capture_request`） | 驼峰（`processCaptureRequest`），参数带 `in/out/oneway` 修饰 |

## 4.5 C HAL 与 AIDL 接口对应关系对照表

> 模块/设备/回调三层逐项对照；"—" 表示 C 接口无对应（AIDL 语义内聚到设备接口）。

| 传统 C HAL（libhardware） | AIDL | 说明 |
|---|---|---|
| `hw_get_module()` + `camera_module_t.common.methods->open(id)` | `ICameraProvider.getCameraDeviceInterface(name)` → `ICameraDevice.open(callback)` | AIDL 由 provider 管理设备发现，open 直接返回 session（不再需要后续 `initialize` 传回调——回调在 open 时一并传入） |
| `camera_module_t.get_number_of_cameras()` | `ICameraProvider.getCameraIdList()`（内置）+ `ICameraProviderCallback.cameraDeviceStatusChange()`（外接动态） | 静态枚举 → 动态上报 |
| `camera_module_t.get_camera_info()` | `ICameraDevice.getCameraCharacteristics()` | 静态特征 |
| `camera_module_t.get_physical_camera_info()` | `ICameraDevice.getPhysicalCameraCharacteristics()` | 物理摄像头特征 |
| `camera_info_t.resource_cost / conflicting_devices` | `ICameraDevice.getResourceCost()`（`CameraResourceCost`） | 多摄并发仲裁 |
| `camera_module_t.set_callbacks(camera_module_callbacks_t*)` | `ICameraProvider.setCallback(ICameraProviderCallback)` | 模块级回调注册 |
| `camera_module_callbacks_t.camera_device_status_change` | `ICameraProviderCallback.cameraDeviceStatusChange` | 设备插拔 |
| `camera_module_callbacks_t.torch_mode_status_change` | `ICameraProviderCallback.torchModeStatusChange` | 手电筒状态 |
| `camera_module_t.set_torch_mode(id, enabled)` | `ICameraDevice.setTorchMode(on)` | 移至设备级 |
| — | `ICameraDevice.turnOnTorchWithStrengthLevel()` / `getTorchStrengthLevel()` | AIDL 新增 |
| `camera_module_t.get_vendor_tag_ops()` | `ICameraProvider.getVendorTags()` | vendor tag 查询 |
| `camera_module_t.open_legacy()` | —（AIDL 无 HAL 版本并存概念） | 遗留能力 |
| `camera_module_t.init()` | —（provider 进程内自行初始化） | |
| `camera_module_t.notify_device_state_change(state)` | `ICameraProvider.notifyDeviceStateChange(deviceState)` | 折叠态等整机状态 |
| `camera_module_t.is_stream_combination_supported()` | `ICameraDevice.isStreamCombinationSupported()` / `isStreamCombinationWithSettingsSupported()` / `ICameraProvider.isConcurrentStreamCombinationSupported()` | 下沉到设备级并扩展 |
| `camera3_device_t`（open 返回的 `hw_device_t*`） | `ICameraDeviceSession` | 设备会话 |
| `camera3_device_ops_t.initialize(callback_ops)` | 回调在 `ICameraDevice.open(callback)` 中传入 | 合并进 open |
| `configure_streams(stream_list)` | `ICameraDeviceSession.configureStreams(StreamConfiguration)` → `HalStream[]` | HAL 诉求由返回值表达（不再回写入参结构体）；V2 版本返回 `ConfigureStreamsRet` |
| `construct_default_request_settings(template)` | `ICameraDeviceSession.constructDefaultRequestSettings(RequestTemplate)`；`ICameraDevice` 上另有 open 前可调用的版本 | |
| `process_capture_request(request)` | `ICameraDeviceSession.processCaptureRequest(CaptureRequest[], BufferCache[])` | 批量提交 + buffer 缓存清理 |
| `flush()` | `ICameraDeviceSession.flush()` | |
| `dump(fd)` | —（dumpsys 走 provider 进程实现，不在接口中） | |
| `register_stream_buffers(buffer_set)`（3.2 废弃） | —（buffer 随 request 传递）或 `ICameraDeviceCallback.requestStreamBuffers/returnStreamBuffers`（HAL buffer manager） | |
| `get_metadata_vendor_tag_ops()` | `ICameraProvider.getVendorTags()` | 移至 provider 级 |
| `signal_stream_flush(stream_ids)`（3.6） | `ICameraDeviceSession.signalStreamFlush(streamIds, streamConfigCounter)`（oneway） | 增加 streamConfigCounter 区分配置轮次 |
| `is_reconfiguration_required(old, new)`（3.6） | `ICameraDeviceSession.isReconfigurationRequired(oldSessionParams, newSessionParams)` | |
| — | `ICameraDeviceSession.switchToOffline()` → `ICameraOfflineSession` | 离线模式为 HIDL 3.7/AIDL 新增 |
| `camera3_callback_ops.process_capture_result(result)` | `ICameraDeviceCallback.processCaptureResult(CaptureResult[])` | 批量 |
| `camera3_callback_ops.notify(msg)` | `ICameraDeviceCallback.notify(NotifyMsg[])` | 批量 |
| `camera3_callback_ops.request_stream_buffers()`（3.6） | `ICameraDeviceCallback.requestStreamBuffers(BufferRequest[]) → BufferRequestStatus, StreamBufferRet[]` | |
| `camera3_callback_ops.return_stream_buffers()`（3.6） | `ICameraDeviceCallback.returnStreamBuffers(StreamBuffer[])` | |
| `camera3_stream_t` | `Stream`（FW→HAL 期望）+ `HalStream`（HAL 诉求） | 输入/输出拆成两个 parcelable |
| `camera3_stream_buffer_t` | `StreamBuffer` | fence fd → `NativeHandle`；新增 `bufferId` 缓存机制 |
| `camera3_capture_request_t` | `CaptureRequest` | 多摄设置 `physcam_settings` → `PhysicalCameraSetting[]` |
| `camera3_capture_result_t` | `CaptureResult` | 多摄 metadata → `PhysicalCameraMetadata[]` |
| `CAMERA3_TEMPLATE_*` | `RequestTemplate` 枚举 | 值相同（PREVIEW=1 … MANUAL=6） |

学习建议：先以 4.3 的 C 接口理解 HAL3 的核心模型（request/result、stream、fence、partial result），再对照 4.4/4.5 阅读 AIDL 定义——两者语义几乎一一对应，差异集中在进程模型、错误处理与批量/FMQ 传输细节上。阅读 framework 侧实现时，`frameworks/av/services/camera/libcameraservice/common/HalInterface.h`（`Camera3Device` 对两种后端的统一封装）与 `CameraProviderManager` 是印证对照关系的最佳入口。

<!-- ch4 done -->
