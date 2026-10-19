# 第 2 章 Trace 抓取与工具链

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 本章是操作手册：怎么抓、抓哪些 category、怎么给 App 打自定义点、抓的时候有哪些坑。命令与配置按 Android 9+ / Perfetto 体系整理（A 层为主）。

## 2.1 方式总览

| 方式 | 适合 | 优点 | 限制 |
|---|---|---|---|
| **Perfetto Web UI**（ui.perfetto.dev 的 Record 页） | 调试机连电脑 | 图形化勾选、自动转换 | 需要设备可被 adb 识别 |
| **Perfetto 命令行**（`adb shell perfetto`） | 脚本化/复现/CI | 可精确控制 config、可自动化 | 需手工写 config |
| **atrace（legacy）** | 快速抓 systrace HTML | 命令短 | HTML 分析能力弱，逐步淘汰 |
| **Android Studio Profiler → System Trace** | 应用开发日常 | 与 Studio 集成、CPU 采样联动 | 相机跨进程分析深度有限 |
| **dumpsys media.camera** | 辅助定位 | 看会话/流/in-flight 状态 | 不是时间轴数据 |

> 相机分析推荐：**Perfetto 命令行/UI 抓完整链路 + dumpsys 对照状态 + logcat 看错误**，三者时间点对齐使用。

## 2.2 Perfetto 配置与抓取

**最小可用配置**（保存为 `camera.cfg`，推到设备）：

```protobuf
# 环形缓冲 64MB，按需覆盖旧数据
buffers { size_kb: 65536 fill_policy: RING_BUFFER }

# 内核 ftrace + atrace 分类
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      atrace_categories: "camera"
      atrace_categories: "camera_memory"
      atrace_categories: "sched"
      atrace_categories: "freq"
      atrace_categories: "idle"
      atrace_categories: "binder_driver"
      atrace_categories: "binder_lock"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "hal"
      atrace_categories: "aidl"
      atrace_categories: "res"
      atrace_categories: "dalvik"
      atrace_categories: "input"
      atrace_apps: "com.example.camera"     # 你的应用包名：开启应用侧 atrace
      buffer_size_kb: 32768
      drain_period_ms: 250
    }
  }
}

# 进程/线程信息（进程名、oom score 等）
data_sources {
  config {
    name: "linux.process_stats"
    process_stats_config { scan_all_processes_on_start: true }
  }
}

# CPU 频率/时间统计
data_sources {
  config {
    name: "linux.sys_stats"
    sys_stats_config {
      stat_period_ms: 1000
      stat_counters: STAT_CPU_TIMES
      stat_counters: STAT_FREQ_TIMES
    }
  }
}

duration_ms: 10000
```

抓取命令（shell 权限即可，多数机型可用）：

```bash
adb push camera.cfg /data/local/tmp/camera.cfg
adb shell perfetto --txt -c /data/local/tmp/camera.cfg -o /data/misc/perfetto-traces/camera.pftrace
adb pull /data/misc/perfetto-traces/camera.pftrace
```

要点：

- 抓取时长用 `duration_ms` 固定，或交互式抓取时靠 `Ctrl+C` 停止（RING_BUFFER 保留最后一段）；
- 输出路径用 `/data/misc/perfetto-traces/`（shell 可写）；部分机型限制其它目录；
- 抓之前先确认数据源可用：`adb shell perfetto --query` 列出当前设备支持的数据源；
- 抓取期间**不要停止操作**——先开 trace，再复现相机启动/拍照动作。

## 2.3 category 选择：抓什么才有用

| category | 内容 | 相机分析里的用途 |
|---|---|---|
| `camera` | CameraService/HAL 的 atrace 打点、相机帧事件 | 启动链路、请求/结果的关键切片 |
| `camera_memory` | 相机 buffer 分配/释放 | 排查首帧前的缓冲分配与内存压力 |
| `sched` | 线程调度（sched_switch/wakeup） | 线程状态、优先级、绑核分析 |
| `freq` | CPU 频率变化 | 大核降频、频率爬升慢 |
| `idle` | CPU 空闲状态 | 判断是否被唤醒频繁/绑在小核 |
| `binder_driver` / `binder_lock` | binder 事务与锁 | 跨进程调用耗时与竞争 |
| `gfx` | 图形（Choreographer、RenderThread、BufferQueue） | 预览/回显的渲染与合成 |
| `view` / `wm` | View 系统与窗口管理 | 首帧上屏、窗口创建 |
| `am` | Activity 管理 | 启动 Activity 的阶段切片 |
| `hal` / `aidl` | HAL/AIDL 调用 | 相机 HAL 的 binder 调用 |
| `res` | 资源加载 | 启动期资源开销 |
| `dalvik` | ART 相关（GC、编译） | 启动期 GC/类加载卡顿 |
| `input` | 输入事件 | 点击拍照的输入延迟 |
| `power` | 电源状态 | 亮屏/省电模式对相机的影响 |
| `audio` | 音频（录视频时） | 音画同步问题 |

取舍原则：**buffer 有限，只开与问题相关的分类**。日常相机排查最小集：`camera + sched + freq + binder_driver + gfx + view + am`。

## 2.4 给 App 打自定义 trace 点

系统只覆盖框架层，业务阶段需要自己打点（A 层 API）：

```java
// 同步区间：包住普通调用
TraceCompat.beginSection("Camera#open");
cameraManager.openCamera(id, callback, handler);
TraceCompat.endSection();

// 异步区间：跨回调（推荐用于启动/拍照链路分段）
TraceCompat.beginAsyncSection("Camera#sessionConfig", 0);   // 在发起配置处
// ... onConfigured 回调里：
TraceCompat.endAsyncSection("Camera#sessionConfig", 0);

// 计数/属性（Perfetto 支持，Android 10+ 的 Trace API）
Trace.beginSection("Camera#capture");
Trace.setCounter("capture#pending", count);
```

规则与坑：

- `beginSection/endSection` 必须成对，异常路径也要 end（否则 slice 悬挂）；
- section 名称用**稳定前缀**（如 `Camera#`）方便在 UI 里搜/在 SQL 里过滤；
- async section 的 cookie 要唯一（同一阶段并发时用自增 ID，避免区间重叠错乱）；
- **release 包默认不输出 trace**：manifest 加 `<profileable android:shell="true" />`（Android 10+）或使用 debuggable 包，才能被 `atrace_apps` 抓到；
- trace 名称会出现在系统 trace 里，不要写敏感信息。

相机启动建议打点（与第 3 章阶段对应）：

| 打点 | 位置 |
|---|---|
| `Camera#open` | openCamera 调用前 → onOpened |
| `Camera#sessionConfig` | createCaptureSession → onConfigured（async） |
| `Camera#firstFrame` | setRepeatingRequest → 首帧 onCaptureStarted（async） |
| `Camera#capture` | 快门点击 → onCaptureCompleted（async） |
| `Camera#imageSave` | onImageAvailable → 写盘完成（async） |

## 2.5 atrace legacy 方式（快速抓取）

```bash
# 10 秒、32MB 缓冲、指定包名与分类
adb shell atrace -a com.example.camera -t 10 -b 32768 camera gfx view wm am sched freq binder_driver > trace.html
```

生成的 HTML 可直接用浏览器打开（内置 Perfetto UI），也可上传 ui.perfetto.dev。适合临时排查；系统性分析建议用 2.2 的 config 方式（可加 FrameTimeline、process_stats 等数据源）。

查看设备支持哪些 tag：

```bash
adb shell atrace --list_categories
```

## 2.6 抓取注意事项（高频坑）

1. **trace 自身有开销**：全量 category + 长时间抓取会改变被测系统（尤其高频 sched/freq 事件），分析结论要结合"轻量 trace 复测"；
2. **buffer 满会覆盖早期数据**：RING_BUFFER 保留尾部；如果复现动作很长，增大 buffer 或缩短抓取窗口；
3. **debug vs release 差异**：debug 包有 JIT/断言/日志开销，启动耗时不能代表线上；用 profileable release 复测；
4. **时间基准对齐**：trace 是设备单调时钟；与 logcat 对照时用同一事件的 slice 名比对，不要用墙上时间；
5. **HAL 打点依赖厂商**：vendor 未开 atrace 时，相机 HAL 内部只有旁证（binder、CPU、驱动），结论标注置信度；
6. **多摄/多进程干扰**：抓之前关掉其他相机类 App，避免相机被抢占或资源竞争污染样本。

## 2.7 分析入口

- **Perfetto UI**（ui.perfetto.dev）：打开 trace，左侧 track 树、顶部搜索、下方 SQL（Query 页）；
- **Android Studio**：Profiler → System Trace 直接看线程/CPU 采样联动；
- **Perfetto SDK/命令行 SQL**：`trace_processor_shell` 对 trace 跑 SQL（第 6 章示例）；
- **FrameTimeline**：UI 里勾选结果中的 Frame Timeline track，看 jank 归属。

下一章开始进入相机场景：Camera 启动性能的 trace 分析。
