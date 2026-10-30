# 第 2 章 进程与 IPC

> 材料分层：A = 官方文档（developer.android.com/guide/components/aidl 等），B = 通行工程知识。↔ 通用题：IPC 板块；↔ APP 题 Q30（AIDL 详解与 demo）。

## 2.1 Android 进程模型

- **Zygote 孵化**：系统启动时 Zygote 进程预加载框架类与资源，之后每个 App 进程都由它 fork——共享只读的框架映射，启动快、内存省（A 层）；
- **沙箱隔离**：每个应用分配独立 Linux UID，进程间默认无共享内存，一切跨界都要走 IPC；
- 进程名：默认为包名；`android:process=":remote"` 为应用私有进程（冒号开头），`.remote` 为全局进程（需共享 UID 才能同进程）。

## 2.2 Binder 原理（面试核心）

一次 Binder 调用的角色分工（B 层通行结论，↔ 系统题 Binder 相关）：

| 角色 | 职责 |
|---|---|
| 客户端代理（Proxy） | 打包参数（序列化）→ 交给驱动 |
| Binder 驱动 | 路由到目标进程，**借 mmap 只拷贝一次**（普通 IPC 如管道/Socket 需两次） |
| 服务端 Stub | 解包 → 执行 `onTransact` → 回写结果 |

关键事实（系统面试题已核对过的事实，直接沿用）：

- 每个进程的 Binder 默认线程池 **15 个线程**（`DEFAULT_MAX_BINDER_THREADS`）——服务端处理慢会耗尽线程池导致远端阻塞；
- 默认同步调用（客户端挂起等待）；`oneway` 异步不等待，但同一 Binder 代理的 oneway 调用在驱动层**仍按序排队**；
- 死亡通知：`linkToDeath`/`unlinkToDeath` + `DeathRecipient`——跨进程断线重连的基础设施；
- 排查工具：`binder` 相关 trace、`binder_transactions`、Perfetto binder 面板（↔ Trace 文档 ch6 SQL）。

## 2.3 AIDL 三件套与代码骨架

三件套（A 层）：

1. `.aidl` 接口文件：声明方法与参数方向；
2. 服务端：继承生成的 `Stub` 实现方法，`onBind` 返回；
3. 客户端：`bindService` → `asInterface(binder)` 拿代理 → 调用。

参数方向符（服务端视角）：

| 方向 | 数据流 | 开销 |
|---|---|---|
| `in` | 客户端 → 服务端 | 一次序列化 |
| `out` | 服务端 → 客户端 | 服务端写，回传时空对象填充 |
| `inout` | 双向 | 两次序列化 |

> 默认即 `in`；`out/inout` 需实现 `Parcelable` 的 writeTo/createFromParcel 对（或用 AIDL 生成的辅助类），仅必要时使用。

服务端断线重连模板（B 层）：

```java
private final IBinder.DeathRecipient mDeathRecipient = () -> rebindService();
private IMyService mRemote;

private void connect(IBinder binder) {
    mRemote = IMyService.Stub.asInterface(binder);
    try {
        binder.linkToDeath(mDeathRecipient, 0);
    } catch (RemoteException e) { rebindService(); }
}
private void rebindService() { /* 延迟后重新 bindService，并 unlink 旧引用 */ }
```

**服务端必须校验调用方**（安全底线，↔ 第 13 章）：`enforceCallingPermission()` / `getCallingUid()` 反查包名白名单——`getCallingPid/Uid` 仅在 onTransact 调用栈内有效，缓存到线程外会失真（B 层）。

## 2.4 IPC 选型对比

| 方式 | 能力 | 性能 | 适用 |
|---|---|---|---|
| Bundle（Intent extras） | 传数据 | 一般 | 启动组件带参 |
| Messenger | 串行消息 | 一般 | 简单排队请求（无需并发） |
| **AIDL** | 完整接口、并发 | **最快（一次拷贝）** | 接口复杂/高频（相机/音频 HAL 同款机制） |
| ContentProvider | 结构化数据 CRUD | 中 | 数据共享给其他 App |
| Broadcast | 一对多事件 | 慢、延迟大 | 系统事件/低频通知 |
| 文件/SharedPreferences | 落盘共享 | 慢 | 非实时、无并发 |
| Socket | 全双工 | 中 | 跨设备/本地守护进程 |

↔ 通用题"Messenger vs AIDL"；本仓库 APP 题 Q30 有完整 AIDL demo。

## 2.5 多进程陷阱清单（B 层）

1. **Application.onCreate 每进程各走一次**：初始化逻辑要按进程名分流（`ActivityThread.currentProcessName()`）；
2. **静态变量/单例不跨进程**：每个进程独立堆，别指望内存同步；
3. SharedPreferences 多进程读写不可靠（无跨进程锁，Android 官方明确不支持）——多进程配置用 ContentProvider 封装或 MMKV（C 层：微信开源，mmap+文件锁）；
4. 文件并发写需自建文件锁或改用 provider；
5. 多进程使内存翻倍、Worker 不共享——**除非必要（独立的推送/WebView 进程隔离崩溃域），尽量单进程**。

↔ 通用题：IPC 板块、Handler 板块"多进程注意事项"。
