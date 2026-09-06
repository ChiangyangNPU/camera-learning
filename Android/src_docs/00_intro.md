# Android Camera 源码链路学习文档（framework / CameraService）

> 本文档是相机学习系列的第三份：前两份分别讲机制（《Android_Camera_学习文档.md》）与接口（《Android_Camera_接口文档.md》），本份深入 **framework 与 CameraService 的源码实现**，把"一次 openCamera → 建会话 → 下发请求 → 收回结果 → 出错恢复"的完整调用链从 AOSP main 分支源码中逐段讲清。
>
> 官方没有专门讲述 CameraService 内部实现的文档，本文全部结论直接出自 AOSP 源码（main 分支，2026-09 时点），每节附 googlesource 来源链接与行号，函数名均经源码核对。所有流程图/时序图使用 Mermaid 语法。

## 文档结构

| 章节 | 内容 | 关键源码 |
|---|---|---|
| 第 1 章 进程模型、源码地图与服务启动 | 三进程模型、源码目录地图、cameraserver 启动、provider 注册与设备枚举、ICameraService.aidl 方法表 | main_cameraserver.cpp、CameraService.cpp、CameraProviderManager.cpp、AidlProviderInfo.cpp |
| 第 2 章 openCamera 完整调用链 | Java 层 openCamera → connectDevice → 权限/优先级仲裁与驱逐 → CameraDeviceClient/Camera3Device 创建 → HAL open | CameraManager.java、CameraService.cpp、ClientManager.h、CameraDeviceImpl.java |
| 第 3 章 createCaptureSession 与请求提交 | 会话创建、流构建与 configureStreams、请求下发、RequestThread 线程模型 | CameraDeviceImpl.java、CameraDeviceClient.cpp、Camera3Device.cpp |
| 第 4 章 结果返回、缓冲区与错误恢复 | shutter/结果双通道、in-flight 匹配、partial result、缓冲区全生命周期、错误分类与恢复 | Camera3OutputUtils.cpp、InFlightRequest.h、AidlCamera3Device.cpp |

## main 分支与旧资料的主要差异（阅读提示）

1. `CameraProviderManager` 移至 `common/`，ProviderInfo 按 HIDL/AIDL 拆分为 `common/hidl/HidlProviderInfo` 与 `common/aidl/AidlProviderInfo`；
2. 框架↔HAL 传输适配独立成类：`libcameraservice/{aidl,hidl}/`、`device3/{aidl,hidl,deprecated}/`；
3. 结果回程管线从 Camera3Device.cpp 抽到 `device3/Camera3OutputUtils.cpp`（`camera3::processCaptureResult/notify/notifyShutter` 等）；
4. `UidPolicy` 并入 CameraService.cpp/h；`CameraDeviceImpl.java` 移入 `camera2/impl/` 子目录；
5. `ICameraService.aidl` 中没有 `getCameraIdList`——API2 的设备列表来自 `addListener` 快照 + 状态回调；
6. `hardware/interfaces/camera/provider/default/` 只剩外接 USB provider，内置 provider 实现下沉到各设备树。

## 建议阅读路径

- 第一次读：按章顺序通读，重点消化第 1 章的三进程模型和第 2 章 openCamera 全链；
- 排障查阅：第 4 章的 in-flight 匹配与错误分类流程图适合对照 dumpsys/log 定位问题；
- 与前两份文档配合：遇到接口签名细节查《接口文档》第 1/4 章，遇到 HAL 协议语义查《学习文档》第 1~3 章。

---
