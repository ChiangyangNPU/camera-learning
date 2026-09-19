# 第 3 章 createCaptureSession 与请求提交链路

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。

> 本章基于 AOSP main 分支真实源码整理，函数名与行为均出自所列源文件，行号为当前 main 分支时点。所有图为 Mermaid 语法。

## 3.1 Java 层：createCaptureSession

来源：[CameraDeviceImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java)

应用层的所有会话创建重载最终都汇入一条私有路径：

- `createCaptureSession(List<Surface>)`（:727）、`createCaptureSessionByOutputConfigurations`（:740）、带输入流的 reprocess 版本（:771、:800）、`SessionConfiguration` 版本（:886）→ 全部调用 **`createCaptureSessionInternal`**（:912）。

`createCaptureSessionInternal` 的关键动作：

```mermaid
flowchart TD
    A["createCaptureSessionInternal(inputConfig, outputs, callback, executor, operatingMode, sessionParams)"] --> B["checkIfCameraClosedOrInError()"]
    B --> C["isConstrainedHighSpeed / isSharedSession 判定<br/>（这两种模式不允许输入流）"]
    C --> D["旧会话收尾<br/>mCurrentSession.replaceSessionClose()<br/>ExtensionSession release"]
    D --> E["configureStreamsChecked(...)<br/>★ 阻塞式配置流：直到设备回到 IDLE 才返回"]
    E --> F{"configureSuccess ?"}
    F -- true --> G{"inputConfig != null ?"}
    G -- 是（reprocess） --> H["input = mRemoteDevice.getInputSurface()"]
    G -- 否 --> I["new CameraCaptureSessionImpl(...)"]
    F -- false --> I
    H --> I
    C --> J["高帧率：CameraConstrainedHighSpeedCaptureSessionImpl<br/>共享会话：CameraSharedCaptureSessionImpl"]
    I --> K["mCurrentSession = newSession<br/>mSessionStateCallback = mCurrentSession.getDeviceStateCallback()"]
```

`configureStreamsChecked`（:602）内部顺序：先 `stopRepeating()`（:642，配置变更前必须停掉旧重复请求），然后对每个 `OutputConfiguration` 调 `mRemoteDevice.createStream(outConfig)`（:680），最后 `mRemoteDevice.endConfigure(operatingMode, sessionParams, ...)`（:687）阻塞等待配置结果。

会话对象分三种实现：普通 `CameraCaptureSessionImpl`、受限高帧率 `CameraConstrainedHighSpeedCaptureSessionImpl`、共享会话 `CameraSharedCaptureSessionImpl`；`onConfigured/onConfigureFailed` 回调由 `CameraCaptureSessionImpl` 构造函数依据 `configureSuccess` 直接分发（CameraCaptureSessionImpl.java:125-129）。

> 注意：Java 层的会话**不是**跨进程对象——真正配置流发生在 `configureStreamsChecked` 阻塞期间；`CameraCaptureSessionImpl` 只是持有回调与引用计数的本地包装。

## 3.2 cameraserver 侧：流构建与 configureStreams

来源：[CameraDeviceClient.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/api2/CameraDeviceClient.cpp)、[Camera3Device.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/device3/Camera3Device.cpp)

```mermaid
flowchart TD
    A["ICameraDeviceUser.createStream(OutputConfiguration)"] --> B["CameraDeviceClient::createStream（CameraDeviceClient.cpp:941）"]
    B --> B1["读取 dynamicRangeProfile / sensorPixelModes / streamUseCase / colorSpace"]
    B1 --> B2{"复合流？<br/>HEIC / JpegR 等由 CompositeStream 包装"}
    B2 -- 是 --> B3["compositeStream->createStream(...)"]
    B2 -- 否 --> B4["mDevice->createStream(surfaceHolders, ... width/height/format/usage ...)"]
    B3 --> C["Camera3Device::createOutputStream<br/>→ new Camera3OutputStream（持 Surface 的 IGraphicBufferProducer）"]
    B4 --> C
    A2["ICameraDeviceUser.endConfigure(operatingMode, sessionParams)"] --> D["CameraDeviceClient::endConfigure（:688）<br/>SessionConfigurationUtils::checkOperatingMode"]
    D --> E["mDevice->configureStreams(sessionParams, operatingMode)"]
    E --> F["Camera3Device::configureStreamsLocked（Camera3Device.cpp:2445）<br/>清空旧流、构建 camera3_stream_configuration_t / AIDL StreamConfiguration"]
    F --> G["mInterface->configureStreams(sessionBuffer, &config, bufferSizes, logId)<br/>（Camera3Device.cpp:2609，经传输适配层）"]
    G --> H{"传输层"}
    H -- AIDL --> H1["device3/aidl/AidlCamera3Device<br/>→ AIDL ICameraDeviceSession.configureStreams"]
    H -- HIDL --> H2["device3/hidl/...<br/>→ HIDL ICameraDeviceSession.configureStreams"]
    H1 --> I["HAL 返回配置后的 maxBuffers / usage<br/>框架回填到每个输出流，配置完成"]
```

要点：

- `createStream` 阶段（binder 调用逐个进行）只做**流对象构建**；真正的 HAL 重配置集中在 `endConfigure` 一次性触发，这就是官方文档"会话参数只在 configure 时生效"的由来。
- `configureStreamsLocked`（:2445）负责：按 operatingMode（NORMAL/HIGH_SPEED/SHARED…）组装流配置、处理输入流（reprocess）、把框架侧的 `dynamicRangeProfile/streamUseCase` 写入流配置，最后经 `mInterface`（AIDL/HIDL 适配器）下发 HAL。
- main 分支传输适配独立成类：AIDL 栈在 `device3/aidl/AidlCamera3Device`，HIDL 栈在 `device3/hidl/`，旧 API1 栈在 `device3/deprecated/`。

## 3.3 请求提交：从 capture() 到 HAL

来源：[CameraDeviceImpl.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/camera2/impl/CameraDeviceImpl.java)、[CameraDeviceClient.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/api2/CameraDeviceClient.cpp)、[Camera3Device.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/device3/Camera3Device.cpp)

```mermaid
flowchart TD
    subgraph APP["app 进程"]
        J1["capture() / setRepeatingRequest() / captureBurst()"] --> J2["submitCaptureRequest(requestList, callback, executor, repeating)<br/>CameraDeviceImpl.java:1479"]
        J2 --> J3["校验：每个 request 至少 1 个非空 Surface"]
        J3 --> J4{"repeating ?"}
        J4 -- 是 --> J5["先 stopRepeating()"]
        J4 -- 否 --> J6["convertSurfaceToStreamId(mConfiguredOutputs)<br/>把 Surface 映射为 streamIdx/surfaceIdx"]
        J5 --> J6
        J6 --> J7["mRemoteDevice.submitRequestList(requestArray, repeating)<br/>→ SubmitInfo（requestId + lastFrameNumber）"]
        J7 --> J8["mCaptureCallbackMap.put(requestId,<br/>new CaptureCallbackHolder(...))"]
    end
    subgraph CS["cameraserver 进程"]
        N1["CameraDeviceClient::submitRequestList<br/>（CameraDeviceClient.cpp:295）"] --> N2["checkPidStatus / 设备存活 / 空列表检查"]
        N2 --> N3["reprocess 校验：必须已配置输入流、<br/>不允许 streaming reprocess、不支持多物理摄像头"]
        N3 --> N4["insertSurfaceLocked：Surface → outputStreamIds + SurfaceMap<br/>查 mConfiguredOutputs 拿物理 id 与动态范围 profile"]
        N4 --> N5["动态范围组合校验（10-bit）<br/>CONTROL_SENSOR_PIXEL_MODE 与流一致性校验"]
        N5 --> N6["mDevice->captureList / setStreamingRequestList<br/>→ Camera3Device::submitRequestsHelper（:836）"]
        N6 --> N7["checkStatusOkToCaptureLocked"]
        N7 --> N8["convertMetadataListToRequestListLocked<br/>生成 RequestList（分配 frameNumber/requestId）"]
        N8 --> N9{"repeating ?"}
        N9 -- 是 --> N10["mRequestThread->setRepeatingRequests(...)"]
        N9 -- 否 --> N11["mRequestThread->queueRequestList(...)"]
        N10 --> N12["waitUntilStateThenRelock(active, kActiveTimeout)<br/>阻塞等待设备进入 ACTIVE 再返回"]
        N11 --> N12
    end
    J7 -- "Binder IPC" --> N1
```

## 3.4 RequestThread：单线程请求泵与关键机制

来源：[Camera3Device.cpp](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/camera/libcameraservice/device3/Camera3Device.cpp)

`Camera3Device` 内部有一个专职的 **RequestThread** 线程，所有请求都经它下发 HAL：

```mermaid
flowchart TD
    A["RequestThread::threadLoop（Camera3Device.cpp:3655）"] --> B["waitIfPaused（:4706）<br/>flush/暂停状态下挂起"]
    B --> C["waitForNextRequestBatch()<br/>按 mBatchSize 取一批请求（HFR 连拍批处理）"]
    C --> D["逐请求覆盖默认行为：<br/>overrideTestPattern / overrideAutoRotateAndCrop /<br/>overrideAutoframing / inject_session_params(flag)"]
    D --> E["updateSessionParameters(mNextRequests[0])<br/>★ 会话参数（如 3A 模式）变化时：<br/>parent->reconfigureCamera() 触发整条管线重配置"]
    E --> F["prepareHalRequests（:3818）<br/>组装 camera_capture_request_t：<br/>从输出流出队缓冲区、填 input buffer（reprocess）、<br/>写入 settings 元数据与输出流列表"]
    F --> G["sendRequestsBatch()<br/>mInterface->processBatchCaptureRequests(requests, &numRequestProcessed)"]
    G --> H["AIDL 栈：AidlCamera3Device::AidlHalInterface<br/>::processBatchCaptureRequests（AidlCamera3Device.cpp:1210）"]
    H --> I["mAidlSession->processCaptureRequest(captureRequests, cachesToRemove, ...)<br/>（AidlCamera3Device.cpp:1315）—— 进入 vendor HAL"]
```

关键机制（均在源码中核实）：

- **线程模型**：应用 `submitRequestList` 只是把请求入队并等待设备切到 ACTIVE；真正与 HAL 的交互全部在 RequestThread 单线程内串行进行，避免多线程竞态。
- **queue 与 repeating 双队列**：单发请求走 `queueRequestList`，重复请求由 `setRepeatingRequests` 替换 `mRepeatingRequests` 列表——每次循环取"最新单发请求优先 + 最新 repeating 请求垫底"（`waitForNextRequestBatch` 的注释明确说明了稳态单 repeating 的优化路径）。
- **会话参数热更新**：`updateSessionParameters` 检测到无法旁路的会话参数变化时，会调用 `parent->reconfigureCamera()` 走完整重配置（这解释了部分参数为何"改了没反应"——它们本质是会话参数）。
- **标识符语义**：`requestId`（Java 回调匹配，`mCaptureCallbackMap` 的 key）、`frameNumber`（该设备单调递增，结果与 shutter 匹配的 key）、`sequenceId`（一个 repeating 序列的标识，repeating 停止时经 `onRepeatingRequestEnd(sequenceId, lastFrameNumber)` 通知 Java 层清理回调表）。
- **异常路径**：`prepareHalRequests` 取输出缓冲区超时只清理该批请求（`cleanUpFailedRequests(sendRequestError=true)`）并 `checkAndStopRepeatingRequest()`，不杀死线程；`cleanUpFailedRequests` 内部会清空 `mRepeatingRequests`（:3258、:3296）。

## 3.5 全链路时序图

```mermaid
sequenceDiagram
    participant App as app 进程<br/>CameraDeviceImpl / 应用线程
    participant CS as cameraserver 进程<br/>CameraDeviceClient / Camera3Device / RequestThread
    participant HAL as provider 进程<br/>ICameraDeviceSession（vendor HAL）

    App->>App: createCaptureSession(SessionConfiguration)
    App->>CS: createStream(OutputConfiguration) ×N
    CS->>CS: CameraDeviceClient::createStream<br/>→ Camera3OutputStream
    App->>CS: endConfigure(operatingMode, sessionParams)
    CS->>HAL: ICameraDeviceSession.configureStreams(config)
    HAL-->>CS: 配置完成（maxBuffers/usage 回填）
    CS-->>App: 会话回调 onConfigured(session)

    App->>App: setRepeatingRequest / capture
    App->>CS: submitRequestList(requests, repeating)
    CS->>CS: 校验（reprocess/动态范围/sensor pixel mode）<br/>→ 分配 requestId/frameNumber
    CS->>CS: RequestThread 入队<br/>（repeating 替换 mRepeatingRequests）
    CS-->>App: SubmitInfo(requestId, lastFrameNumber)
    CS->>CS: threadLoop：prepareHalRequests<br/>（出队输出缓冲区）
    CS->>HAL: processCaptureRequest(requests, cachesToRemove)
    HAL-->>CS: notify(Shutter, frameNumber)
    CS-->>App: onCaptureStarted(requestId, timestamp, frameNumber)
    HAL-->>CS: processCaptureResult(result + buffers)
    CS-->>App: onCaptureCompleted(TotalCaptureResult) / Surface 帧到达
    App->>CS: stopRepeating()
    CS-->>App: onRepeatingRequestEnd(sequenceId, lastFrameNumber)
    CS->>HAL: （重复请求停止后管线排空）
```

---

> 下一章（第 4 章）讲结果的回程：`processCaptureResult`/`notify` 如何穿过 RequestThread 与 Camera3Device 回到应用回调，缓冲区的出队与归还全生命周期，以及错误处理与恢复机制。
