# 第 3 章 Handler 与线程

> 材料分层：A = 官方 Handler/进程线程文档，B = 通行源码知识（AOSP main 已核对：Android 17 起 MessageQueue 有无锁实现优化，见第 14 章版本表）。↔ 通用题：Handler 板块、性能板块。

## 3.1 四件套与线程绑定

| 组件 | 职责 |
|---|---|
| Looper | 每线程一个，循环取消息并分发（`Looper.prepare()` 创建 + `loop()` 开跑） |
| MessageQueue | Looper 内部的按 `when` 排序的消息链表 |
| Message | 消息本体（what/arg/obj/target/when/callback），可用 `obtain()` 池化复用 |
| Handler | 发送（post/send）与处理（handleMessage）的统一门面 |

关键事实：

- 主线程的 Looper 在 `ActivityThread.main()` 里 `prepareMainLooper()` 创建——**App 主线程就是一个消息循环驱动的线程**，一切主线程回调（生命周期/输入/绘制）都是 Message；
- 每线程一个 Looper 靠 **ThreadLocal** 存取（`sThreadLocal.get()`）；子线程用完必须 `quit()`，否则 loop() 永不退出（泄漏）。

## 3.2 取消息：next() 的阻塞原理

`MessageQueue.next()` 的行为（源码级，B 层）：

1. 队头无消息 → `nativePollOnce(-1)` 无限阻塞（底层 epoll 等待，**不烧 CPU**，这就是"空转不耗电"的答案）；
2. 队头是延迟消息 → 按剩余时间 `nativePollOnce(timeoutMillis)` 定时唤醒；
3. 有到期消息 → 摘链返回；
4. 取到**同步屏障**（target == null 的特殊消息）时跳过同步消息，只找异步消息（见 3.4）。

## 3.3 分发：dispatchMessage 三分支（伪码）

```java
public void dispatchMessage(Message msg) {
    if (msg.callback != null) {              // post(Runnable) 发的
        handleCallback(msg);                 //   → 走 Runnable.run()
    } else {
        if (mCallback != null) {             // new Handler(callback) 构造的
            if (mCallback.handleMessage(msg)) return;
        }
        handleMessage(msg);                  // → 子类覆写的 handleMessage
    }
}
```

> 面试高频："post 的消息和 sendMessage 的消息最终都进同一个队列，区别只在分发分支"。

## 3.4 同步屏障与 VSync（与渲染衔接）

- **同步屏障**：`ViewRootImpl.scheduleTraversals()` 在等待 VSync 时插入屏障，把"绘制消息"标记为异步优先执行——保证 UI 绘制不被普通消息插队（↔ 系统题已核对：屏障由 ViewRootImpl 调度；↔ 源码链路文档 5.4.3 VSync 三相位）；
- **泄漏级坑**：若屏障插入后异常路径未移除，主线程所有普通消息被饿死 → 全局卡死（B 层）；
- **IdleHandler**：队列空闲时回调（适合一次性轻量任务如延迟日志），可能不执行或多次执行，不要放关键逻辑。

## 3.5 泄漏与治理

- 泄漏链：非静态 Handler（内部类）持有 Activity → 消息排队期间 Message.target 引用 Handler → Activity 无法回收（↔ 通用题 Q16 有治理 demo）；
- 治理：静态 Handler + WeakReference 持 Activity；或 `Handler(Looper.myLooper())` + 在 onDestroy `removeCallbacksAndMessages(null)`；现代方案直接 `lifecycleScope`/`viewModelScope` 协程替代裸 Handler（↔ 第 6 章）。

## 3.6 线程方案对比

| 方案 | 特点 | 适用 |
|---|---|---|
| HandlerThread | 自带 Looper 的串行线程 | 串行任务队列（Camera2 回调线程即此用法，↔ Camera2Demo） |
| Executor 家族 | newFixedThreadPool(固定 N)/cached(可增长)/single(串行) | 并发任务池 |
| Kotlin 协程 | 挂起式、结构化取消 | 现代 App 默认（↔ 第 6 章） |
| IntentService | 已废弃 → WorkManager | 后台任务 |

线程池参数语义（B 层）：`corePoolSize` 常驻、`maximumPoolSize` 上限、队列满才扩容——`newFixedThreadPool` 队列无界，OOM 风险；`newCachedThreadPool` 上限 Integer.MAX_VALUE，线程爆炸风险。生产用 `ThreadPoolExecutor` 显式指定有界队列 + 拒绝策略。

## 3.7 主线程卡顿监控（三种手段）

| 手段 | 原理 | 粒度 |
|---|---|---|
| LooperPrinter | `setMessageLogging` 打印 `>>>>> Dispatching` / `<<<<< Finished`，计算间隔 | 每条消息耗时 |
| Choreographer.FrameCallback | 每帧回调对比相邻帧间隔 | 掉帧检测 |
| FrameMetrics（API 24+） | 每帧各阶段耗时（measure/layout/draw…） | 帧分阶段（↔ 性能文档 ch2 代码） |

## 3.8 ANR 机制

| 场景 | 超时（前台） |
|---|---|
| 输入事件 5 秒无响应 | 5s |
| 前台广播 onReceive | 10s（Android 14 起有加速收紧） |
| 前台 Service 生命周期回调 | 20s |

- 判定本质：**主线程消息循环被占死**（耗时计算/同步 binder/锁竞争/大量 SP 写入）导致输入或生命周期消息无法及时处理；
- 取证：`/data/anr/` 下 trace 文件（`adb bugreport` 可取），看 main 线程栈与持锁者（↔ Trace 文档 ch5 归因方法）。

↔ 通用题：Handler 板块全部、"ANR 发生机制"（↔ 性能文档第 8 章 ANR 反模式）。
