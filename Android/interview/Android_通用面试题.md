# Android 通用面试题与答案

> 与相机系列的《Android\_Camera\_APP\_面试题》互补的**通用 Android 八股题**，覆盖组件/Handler/View/IPC/协程/性能/Jetpack/Kotlin/网络/ART。按高频考点整理，答案给到"能答出面试官追问"的深度。
> 难度标注：★ 基础必会 / ★★ 中级 / ★★★ 高级加分项。

## 目录

* [一、Activity 与生命周期](#一activity-与生命周期)
* [二、Fragment](#二fragment)
* [三、Handler 消息机制](#三handler-消息机制)
* [四、View 体系与绘制](#四view-体系与绘制)
* [五、进程、IPC 与系统组件](#五进程ipc-与系统组件)
* [六、协程与 Flow](#六协程与-flow)
* [七、性能优化与稳定性](#七性能优化与稳定性)
* [八、存储与数据](#八存储与数据)
* [九、Jetpack 与架构](#九jetpack-与架构)
* [十、Kotlin 与 Compose](#十kotlin-与-compose)
* [十一、网络](#十一网络)
* [十二、ART 与底层](#十二art-与底层)

***

## 一、Activity 与生命周期

### Q1. 完整生命周期与常见场景的表现？ ★

`onCreate → onStart → onResume`（可见可交互）；离开：`onPause → onStop`（onDestroy 销毁）。场景：**弹透明 Activity/对话框主题**只到 onPause（部分可见）；跳普通 Activity 到 onStop；**按 Home** 到 onStop；**锁屏** 到 onStop。注意 Android 10+ 多窗口下"可见但非焦点"状态靠 `onTopResumedActivityChanged` 表达，onPause 不再等价"不可见"。

### Q2. 四种 launchMode 与任务栈？ ★★

* `standard`：每次新实例；`singleTop`：栈顶复用（走 onNewIntent，防连点）；
* `singleTask`：栈内复用，其上 Activity 全部出栈，清栈可见 onNewIntent + onRestart；默认独立 task（taskAffinity）；
* `singleInstance`：独占一个任务栈，其他 Activity 进不了该栈。

配合 `taskAffinity`、`allowTaskReparenting`、FLAG\_ACTIVITY\_CLEAR\_TOP/CLEAR\_TASK 考追问。onNewIntent 后要调 setIntent 或自行处理数据刷新。

### Q3. 进程被杀后重建，Activity 如何恢复？与"重新启动"的区别？ ★★

内存不足/后台被杀走 onSaveInstanceState → 系统记录 → 冷启动恢复：`onCreate(savedInstanceState)` 与 `onRestoreInstanceState` 收回数据（View 层次自动恢复 id 标记的状态，onRestore 在 onStart 之后调用保证先恢复 View 再恢复业务）。区别于配置变更：屏幕旋转默认是**销毁重建**（同机制），声明 `configChanges="orientation|screenSize"` 则不重建只回调 `onConfigurationChanged`（一般建议交给重建 + ViewModel 承载数据）。

### Q4. Activity 的启动流程（Framework 视角）？ ★★★

Launcher/startActivity → AMS（ATMS）解析 intent、栈管理 → 若目标进程不存在 **Zygote fork**（共享预加载资源 COW）→ ActivityThread.main 建立 main Looper → ApplicationThread Binder 回调 → `handleLaunchActivity`：`performLaunchActivity`（newActivity 反射创建、attach、Instrumentation 调 onCreate）→ `handleResumeActivity`（WindowManager.addView → ViewRootImpl → 首帧 measure/layout/draw）。追问考点：为什么反射创建 Activity（插件化 hook 点）、Instrumentation 的角色。

### Q5. App 点击图标到首帧，中间发生了什么？（冷启动链路） ★★★

Zygote fork → ART 初始化（含 Baseline Profile 预编译效果）→ Application.attachBaseContext/onCreate（三方 SDK 初始化是常见瓶颈）→ 首个 Activity 创建与首帧绘制 → 上屏。度量以 **TTID/TTFD**（初始/完整显示时间）为准；优化见 Q43。追问：为什么 Application.onCreate 放主线程重活会拖首帧（同步串行在 Activity 创建之前）。

### Q6. startActivityForResult 过时了吗？现在怎么传结果？ ★

旧 API 被 **ActivityResultLauncher** 取代：`registerForActivityResult(StartActivityForResult())` 注册契约，launch 启动，回调拿 resultCode/data。生命周期安全（注册时机在 STARTED 前即可，内部自动处理重建恢复）。权限申请同理 `RequestPermission()` 契约。

***

## 二、Fragment

### Q7. Fragment 的 viewLifecycleOwner 是什么？为什么观察 LiveData 要用它？ ★★

Fragment 实例存活于返回栈，而其 **View 会被销毁重建**（onDestroyView）。用 `viewLifecycleOwner` 观察数据，保证 View 销毁时停止订阅，避免重复 observe、更新已销毁 View 导致的崩溃或泄漏；用 fragment 自身做 owner 是高频 bug 源。

### Q8. Fragment 与 Activity 的通信方式？ ★★

推荐顺序：**共享 ViewModel**（activityViewModels / 同 ViewModelStoreOwner）做数据共享；**FragmentManager Result API**（`setFragmentResultListener`）做一次性结果；接口回调（传统）用于强耦合场景；反模式是直接持有引用与广播。父子 Fragment 间用 childFragmentManager 边界的 owner 隔离。

### Q9. Fragment 事务的常见坑？ ★★

* `commit()` 与 `commitNow()`（同步执行）差异；allowingStateLoss 滥用掩盖状态丢失 bug；
* 在 `onSaveInstanceState` 之后 commit 抛 IllegalStateException（状态丢失保护）；
* 返回栈 + View 销毁：动画与 sharedElement 在 pop 时的时序；
* 嵌套 Fragment 的 childFragmentManager 与 findFragmentById 作用域。

### Q10. ViewPager2 + Fragment 的页面生命周期与缓存？ ★★

`FragmentStateAdapter`：不可见页面走 onDestroyView（View 销毁，实例留返回栈），比 ViewPager1 的 attach/detach 更彻底；离屏缓存由 `offscreenPageLimit` 控制：默认 `OFFSCREEN_PAGE_LIMIT_DEFAULT`(-1) 时交给 RecyclerView 缓存策略，设为 ≥1 则按数量保留相邻页面（页面不再销毁，用内存换流畅）。`BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT`（等效 RESUME\_ONLY\_CURRENT\_FRAGMENT）保证只有当前页 onResume——懒加载的正确姿势是结合 viewLifecycleOwner 与重复观察去重，而非在 setOnPageChangeListener 里手动判断。

***

## 三、Handler 消息机制

### Q11. Looper / MessageQueue / Handler / ThreadLocal 的关系？ ★

每线程一个 Looper（`Looper.prepare` 绑定到 `ThreadLocal`），Looper 持有一个 MessageQueue；Handler 发消息时把 Message 插入其创建线程的队列（target 指向自己），Looper.loop() 死循环取消息 `msg.target.dispatchMessage` 分发。主线程的 Looper 在 ActivityThread.main 里 prepare 好（`Looper.getMainLooper()`）。

### Q12. 主线程 Looper 死循环为什么不会 ANR？ ★★

ANR 的本质是**消息没有在窗口期内被处理**（特定消息超时），而不是"循环本身阻塞"。主线程 Looper 空闲时线程沉睡（队列无消息时 `nativePollOnce` 阻塞，底层 epoll），有消息被唤醒处理——这是事件驱动模型的正常形态；ANR 监控的是"某类消息（输入事件/广播/Service 回调）处理超时"，与 loop 常驻互不矛盾。

### Q13. 消息屏障（sync barrier）是什么？Choreographer 怎么用它？ ★★★

屏障是 target 为 null 的特殊消息，插队后队列**只执行异步消息**，同步消息全部让路。ViewRootImpl 在 `scheduleTraversals()` 时向主线程队列插入屏障，随后 Choreographer 以异步消息（`setAsynchronous(true)`）调度输入/动画/绘制回调，得以插到普通消息之前，`doTraversal()` 时移除屏障——保证掉帧时绘制不被业务消息挤占。Android 12+ 绘制路径有重构，但屏障机制与异步消息仍是理解渲染调度的基础。

### Q14. IdleHandler 是什么？什么时候用？ ★★

MessageQueue.addIdleHandler：队列空闲（无消息或只剩屏障/延迟消息）时回调一次（返回 false 自动移除）。用途：把非紧急工作延后到空闲（如 post 布局后的二次统计、预加载）。注意：可能一直不回调（忙时）或立即回调（启动即空闲），不适合关键路径。Looper.getMainLooper().queue 的 isIdle 队列 API 是系统侧扩展。

### Q15. Message 的复用池怎么工作？为什么建议 obtain() 而不是 new？ ★★

Message 内部维护全局 sPool 单链表（MAX\_POOL\_SIZE=50）：recycle 时归还，obtain 时复用头结点。高频发消息场景减少对象分配与 GC 压力。坑：不能复用已入队消息；recycle 后字段归零，不要保留引用。

### Q16. Handler 导致内存泄漏的典型场景与治理？ ★

非静态内部类 Handler 隐式持有 Activity，延迟消息未处理时 Activity 无法回收。治理：static + WeakReference、`onDestroy` 里 `removeCallbacksAndMessages(null)`、用 `Handler(Looper.getMainLooper())`（弃用无 Looper 构造）+ 显式退出。同族考点：RxJava/协程的 dispose/cancel 对应关系。

**治理 demo（Java）**：

```java
// ❌ 错误示范：匿名/非静态内部类 Handler 隐式持有 Activity；
// 60s 的延迟消息还在队列里时，Activity 被销毁也无法回收（LeakCanary 必报）
public class LeakyActivity extends AppCompatActivity {
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public boolean handleMessage(Message msg) {
            textView.setText("tick");          // 隐式持有 LeakyActivity.this
            return true;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler.sendEmptyMessageDelayed(MSG_TICK, 60_000);
    }
}

// ✅ 正确示范：static 嵌套类 + WeakReference + onDestroy 清理
public class SafeActivity extends AppCompatActivity {

    private static class SafeHandler extends Handler {
        private final WeakReference<SafeActivity> ref;   // 弱引用不阻止 GC

        SafeHandler(SafeActivity activity) {
            super(Looper.getMainLooper());
            this.ref = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            SafeActivity activity = ref.get();
            if (activity == null) return;                 // Activity 已回收：丢弃消息
            activity.render();
        }
    }

    private final SafeHandler handler = new SafeHandler(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler.sendEmptyMessageDelayed(MSG_TICK, 60_000);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);         // 关键：清空延迟消息与回调
        super.onDestroy();
    }

    private void render() { /* 刷新 UI */ }

    private static final int MSG_TICK = 1;
}
```

要点：`WeakReference` 与 `removeCallbacksAndMessages(null)` 是**双保险**——前者兜底"忘了清"，后者兜底"消息本身仍需及时释放"；Fragment 中与 View 相关的消息应在 `onDestroyView` 清理（View 生命周期短于 Fragment）；自定义 HandlerThread 还要在销毁时 `quitSafely()` 退出线程。Kotlin 项目还有更省心的替代：`lifecycleScope.launch { delay(60_000); render() }`，随生命周期销毁自动取消，无需手动清理。

***

## 四、View 体系与绘制

### Q17. measure/layout/draw 三大流程？MeasureSpec 怎么读？ ★★

ViewRootImpl 触发 `performTraversals`：measure 自顶向下递归（父容器把约束打包成 `MeasureSpec(mode+size)`：EXACTLY/AT\_MOST/UNSPECIFIED）→ layout 确定位置（onLayout 由容器实现摆放子 View）→ draw（背景 → onDraw → 前景/滚动条 → 子 View dispatchDraw）。View 的 wrap\_content 默认实现有问题，自定义 View 必须在 onMeasure 里处理 AT\_MOST（给出内容尺寸）。

### Q18. 事件分发机制？滑动冲突怎么解？ ★★

链路：Activity → Window → ViewGroup.dispatchTouchEvent → onInterceptTouchEvent → 子 View\.dispatchTouchEvent → onTouchEvent；`onTouch`（Listener）优先于 `onTouchEvent`；DOWN 决定"谁消费"，后续 MOVE/UP 直接派发给 DOWN 时命中的 target（child 用 `requestDisallowInterceptTouchEvent` 抢权）。冲突解法模板：外部拦截法（父容器 onIntercept 按滑动方向判断）、内部拦截法（子 View 在 dispatch 中配合 disallow）、嵌套滑动 NestedScrolling（ parental-child 协作 API，RecyclerView/CoordinatorLayout 用它）。

### Q19. requestLayout / invalidate / postInvalidate 区别？ ★

`requestLayout`：布局变化（尺寸/位置可能变），从该 View 向上标脏 PFLAG\_FORCE\_LAYOUT 并触发测量+布局+重绘；`invalidate`：仅重绘（标脏绘制区，触发 draw），必须在主线程；`postInvalidate`：非线程安全的线程版重绘。调用 requestLayout 而尺寸没变时不会重复 measure（跳过优化），但若在 onLayout 里反向改尺寸易造成无限循环。

### Q20. SurfaceView / TextureView 区别？相机预览为什么常用 SurfaceView？ ★★★

* **SurfaceView**：独立窗口层（Surface 由 WMS 合成），绘制不走 View 层级的硬件层，可在独立线程渲染，零拷贝上屏；缺点：不能做普通 View 变换（变形/裁剪受限）、有"打洞"层叠限制；
* **TextureView**：普通 View 节点（GPU 纹理），支持变换/动画/裁剪，但每帧经 GPU 上传合成，多一层拷贝，延迟与功耗更高。

相机预览优先 SurfaceView：低延迟 + 直通 `SurfaceTexture`/Surface 到 WMS 合成（这与相机章节的 PRIV 直通流呼应）；需要预览叠加动画时才用 TextureView。

### Q21. Window / WindowManager / ViewRootImpl / WMS 的关系？ ★★★

Window 是抽象（唯一实现 PhoneWindow），每 Window 一个 ViewRootImpl（View 树的"宿主 + 会话"）：measure/layout/draw 的发起者、与 **WMS（system\_server）** 的 IWindowSession 通信者、Surface 分配的请求方（relayout 返回 Surface）。addView 即创建 ViewRootImpl 并注册到 WMS；触摸事件链的源头也由 WMS 经 InputChannel 投递。追问：Dialog 为什么不能直接用 Application Context 的 Window token（token 归属）。

### Q22. RecyclerView 四级缓存？ ★★

* **Scrap**（attach 期临时回收，直接复用不重绑）；
* **CachedViews**（默认 2 个，按 position 复用，不重绑）；
* **ViewCacheExtension**（自定义层，极少用）；
* **RecycledViewPool**（按 viewType 复用，需重绑定，多 RecyclerView 可共享）。

NotFound 时 Adapter.onCreateViewHolder。配合 Prefetch（GapWorker 提前创建）与 setHasFixedSize 优化。追问：为什么多 viewType 多了性能差（Pool 按 type 分桶，命中率降）。

### Q23. 硬件加速下 View 的绘制模型？ ★★★

开启硬件加速后 draw 产出的是 **RenderNode/DisplayList**（记录绘制指令），由 RenderThread（与主线程分离）执行 GPU 渲染——所以主线程只"录制"，重绘成本低、动画可脱离主线程（RenderThread 动画）。invalidate 只重录脏节点的 DisplayList。追问：为什么某些 Canvas API 硬件加速不支持（录制器实现范围）。

***

### Q24. 自定义 View 有哪几种方式？分别适合什么场景？ ★★

| 方式                  | 做法                                               | 适合                                      | 核心重写                            |
| ------------------- | ------------------------------------------------ | --------------------------------------- | ------------------------------- |
| 组合控件                | 继承 FrameLayout/LinearLayout，构造器里 addView/inflate | 多个原生控件拼装（标题栏、输入框、空态页）                   | 无（或按需暴露接口）                      |
| 继承已有控件              | `extends TextView/ImageView` 等                   | 微调原生行为/外观（带清除按钮的 EditText、圆角 ImageView） | 按需 onDraw/onTouchEvent          |
| 继承 View（完全自绘）       | `extends View`                                   | 单控件绘制（进度条、图表、签名板、水印）                    | onMeasure + onDraw              |
| 继承 ViewGroup（自定义布局） | `extends ViewGroup`                              | 自定布局规则（FlowLayout、九宫格、瀑布流）              | onMeasure + onLayout + 遍历子 View |

选择依据三问：**要不要自己画像素（onDraw）？要不要决定子 View 的位置（onMeasure/onLayout）？还是只拼装现成控件？** 三者组合即得唯一选项。

追问：为什么业务里"组合控件优于自绘"是默认答案——系统控件自带无障碍、输入法、RTL、主题与状态能力，自绘这些全部要自己补。

### Q25. 自定义 View 的标准流程？onMeasure 里 wrap\_content 和 padding 怎么处理？ ★★

**构造器**：`View(Context)`、`View(Context, AttributeSet)`（XML inflate 走这个）、`View(Context, AttributeSet, int defStyleAttr)`（支持主题默认样式）；自定义属性的读取放在 attrs 构造器里。

**onMeasure（自绘 View 必须重写）**：

* 系统默认 `View.onMeasure` 的 `getDefaultSize` 只区分 EXACTLY/AT\_MOST——**AT\_MOST 直接返回父给的可用尺寸**，不重写则 wrap\_content 会占满父容器；
* 正确写法：期望尺寸 = 内容尺寸 + padding，再交给 resolveSize 钳制：

```kotlin
val desiredW = contentWidth + paddingLeft + paddingRight
val desiredH = contentHeight + paddingTop + paddingBottom
setMeasuredDimension(
    resolveSize(desiredW, widthMeasureSpec),
    resolveSize(desiredH, heightMeasureSpec)
)
```

`resolveSize`：EXACTLY 取规定值，AT\_MOST 取 min(desired, available)，UNSPECIFIED 取 desired——三种模式全覆盖。

**onLayout（ViewGroup 必须重写）**：遍历子 View，逐个用 `getChildMeasureSpec(parentSpec, padding + 已占用空间, childDimension)` 推导子约束并 `child.measure()`，再 `child.layout(l, t, r, b)` 摆放。

**onDraw**：绘制时叠加 padding 偏移（`getPaddingLeft()` 等），脏区用 clipRect 限定。

**配套回调**：`onSizeChanged`（尺寸变化时重建 Path/Shader 等缓存）、`onSaveInstanceState/onRestoreInstanceState`（保存自定义状态，View 必须有 id 才会触发）、`onTouchEvent`（自处理交互）。

追问：改状态后该 invalidate 还是 requestLayout——只影响外观用前者，影响尺寸/布局用后者（见 Q19）。

### Q26. 自定义属性怎么定义和读取？TypedArray 为什么必须 recycle？ ★★

定义（`res/values/attrs.xml`）：

```xml
<declare-styleable name="CircleProgressBar">
    <attr name="cpbColor" format="color" />
    <attr name="cpbWidth" format="dimension" />
    <attr name="cpbMode" format="enum">
        <enum name="normal" value="0" />
        <enum name="dashed" value="1" />
    </attr>
</declare-styleable>
```

读取：`obtainStyledAttributes(attrs, R.styleable.CircleProgressBar)` → `getColor/getDimensionPixelSize/getInt`。属性来源优先级：**XML 直接属性 > style > theme**（defStyleAttr/defStyleRes 沿构造器链传递）。

recycle：官方 API 契约要求用完 `recycle()`。TypedArray 内部持有资源解析结果与 Theme 引用，批量实例化场景（列表滚动反复创建）不释放会造成资源占用滞留；标准写法 try/finally 或 `use {}` 保证释放。

追问：`getDimensionPixelSize`（按密度换算取整为 px，绘制用）与 `getDimension`（浮点 px）的区别；`format="enum"` 与 `flag`/位或组合的读法。

### Q27. onDraw 有哪些性能陷阱？为什么不能在 onDraw 里创建对象？ ★★★

* **每帧调用**：onDraw 随 invalidate + VSync 反复执行，内部 new Paint/Path/Rect/字符串会在稳态下持续产生垃圾 → GC 抖动 → 掉帧；全部预分配为成员变量；
* **缓存重计算**：文字排版（StaticLayout）、路径、图片解码在 onSizeChanged/数据变更时算好，onDraw 只做绘制；
* **局部刷新**：`invalidate(l, t, r, b)` 标脏脏区，onDraw 内 `canvas.save() → clipRect(脏区) → 绘制 → restore()`；脏区越小重绘成本越低；
* **过度绘制**：Debug GPU Overdraw 检查；去掉不必要的背景；ViewGroup 默认跳过自身 onDraw（willNotDraw=true），自定义 ViewGroup 要画背景/内容时需 `setWillNotDraw(false)`，否则重写的 onDraw 不执行；
* **硬件加速限制**：部分 Canvas 能力在硬件加速下不支持或受限（典型：某些 Xfermode/BlurMaskFilter，低版本上 clipPath 受限）——必要时对单个 View `setLayerType(LAYER_TYPE_SOFTWARE, null)` 降级，不要对整个界面关闭（代价大）；
* **动画**：优先属性动画改 translation/alpha（RenderThread 驱动，不触发 onDraw），避免"每帧 invalidate 重绘"式动画；
* save/restore 成对调用，防止变换状态泄漏到后续绘制。

***

## 五、进程、IPC 与系统组件

### Q28. Android 进程优先级（oom\_adj）体系？ ★★

前台（拥有可见 Activity/前台服务）> 可见 > 服务 > 缓存（LRU 列表）。Android 9 起引入 **Cached App Optimizer**，Android 11 起默认启用缓存进程冻结（cached apps freezer，基于 cgroup freezer）：缓存进程被冻结后完全停止运行。系统按优先级从低到高杀进程回收内存（LMK）。相机侧关联：后台进程的相机 client 会被 CameraService 主动驱逐（对应相机面试题 Q3）。

### Q29. Binder 为什么一次拷贝？原理是什么？ ★★★

传统 IPC 两次拷贝（用户 A→内核→用户 B）；Binder 用 **mmap 把目标进程的用户空间与内核缓冲区映射到同一物理页**，发送方拷贝一次到该物理内存，接收方直接可见（零二次拷贝）。其余要点：Client/Server/ServiceManager/驱动四角色、句柄转换、AIDL 生成 Stub/Proxy、 Binder 线程池（libbinder 默认上限 **15** 个线程，驱动按需创建，主线程可通过 joinThreadPool 加入）与同步事务栈深限制。追问：为什么 Binder 线程池上限默认是 15（DEFAULT_MAX_BINDER_THREADS，system_server 等重负载进程会调大），大事务限制（1MB 缓冲共享池，TransactionTooLargeException）。

### Q30. AIDL / Messenger / Bundle 的选用？ ★★

**选型一句话**：要"调用方法"用 AIDL；要"发消息"用 Messenger；只"传一次数据"用 Bundle/Intent。

| 方式            | 本质                               | 通信模型                                | 适合场景                       |
| ------------- | -------------------------------- | ----------------------------------- | -------------------------- |
| AIDL          | Binder 接口描述语言 + 编译期代码生成          | 方法调用（同步 / oneway），支持多方法、返回值、回调、复杂类型 | 高频、并发、业务化的跨进程接口（SDK、跨进程服务） |
| Messenger     | 基于 AIDL 的官方封装（内部就是 `IMessenger`） | 只能发 `Message`，服务端 Handler 串行处理      | 低频指令/状态通知，不想处理并发           |
| Bundle/Intent | 一次性数据传递                          | 无连接、无返回                             | 启动组件、简单传参                  |

大文件三者都不走：用 fd（`ParcelFileDescriptor`）+ 共享内存（`MemoryFile`）或文件映射，不往 Bundle 塞 `byte[]`（1MB 事务限制，见 Q29）。

#### AIDL 深入

**1）一次 AIDL 调用发生了什么**

* `.aidl` 接口经编译生成两个核心类：**`Stub`**（继承 `Binder` 的服务端基类，`onTransact` 负责解包）与 **`Proxy`**（客户端代理，把参数写进 `Parcel` 后 `transact`）；
* 客户端调 `service.add(1, 2)` → `Proxy` 打包参数 → Binder 驱动投递到服务进程 → Binder 线程池取事务 → `Stub.onTransact` 解包 → 调真正的实现 → 结果写回 reply → 客户端 `transact` 返回；
* **同进程时** `Stub.asInterface(binder)` 直接返回 Stub 本体，调用退化为普通方法调用，不经过 IPC——这是"同进程性能几乎无损"的原因。

**2）线程模型（面试必考）**

* 服务端实现运行在 **Binder 线程池**（默认上限 15 线程），**并发进入**，共享状态必须自己保证线程安全；
* 客户端**同步调用会阻塞当前线程**：主线程上做同步 IPC 是卡顿/ANR 高发点，放子线程或改用 `oneway`；
* `oneway`：单向异步，调用方不等待结果；服务端按接收顺序串行执行。它不是"可靠送达"——进程死亡边界上可能丢，有返回值或需要错误处理就别用；
* 回调方向：服务端调客户端传入的 `IInterface`，代码执行在**客户端进程的 Binder 线程**，更新 UI 必须切主线程。

**3）数据类型与方向标签**

* 支持基本类型、`String`/`CharSequence`、`List`/`Map`、`Parcelable`、`IBinder`；
* `Parcelable` 需在 .aidl 里用 `parcelable X;` 声明（包名与 Java 类一致）；
* `in / out / inout`：`in` 客户端→服务端拷贝，`out` 服务端回填，`inout` 双向两次拷贝——大对象优先 `in`，别滥用 `inout`。

**4）回调与死亡管理**

* 回调注册用 **`RemoteCallbackList`**（线程安全、binder 死亡自动清理），不要用 `ArrayList`；
* 客户端用 `linkToDeath` 监听服务进程死亡（`onServiceDisconnected` 是连接级通知，进程崩溃时 binder 死亡更可靠）；
* 警惕双向调用死锁：A 调 B 的同时 B 调 A，两边 Binder 线程互等——回调改 `oneway` 或异步化。

**5）权限与可见性**

* Service 用 `android:permission` 声明调用方权限，服务端再用 `checkCallingPermission/Uid` 二次校验；
* Android 11+ 包可见性：绑定**其他应用**的服务需在 `<queries>` 声明（同应用显式组件不受影响）。

#### AIDL demo（Java）

文件位置：`app/src/main/aidl/com/example/ipc/ICalcService.aidl`、`IRemoteCallback.aidl`。

```aidl
// IRemoteCallback.aidl
package com.example.ipc;

oneway interface IRemoteCallback {
    void onResult(int value);
}
```

```aidl
// ICalcService.aidl
package com.example.ipc;

import com.example.ipc.IRemoteCallback;

interface ICalcService {
    int add(int a, int b);                     // 同步：有返回值，客户端阻塞等待
    void registerCallback(IRemoteCallback cb);
    void unregisterCallback(IRemoteCallback cb);
}
```

服务端（`Service` 返回 `Stub`）：

```java
public class CalcService extends Service {

    private final RemoteCallbackList<IRemoteCallback> callbacks = new RemoteCallbackList<>();

    private final ICalcService.Stub binder = new ICalcService.Stub() {
        @Override
        public int add(int a, int b) {                 // 运行在 Binder 线程池（可能并发）
            int result = a + b;
            int n = callbacks.beginBroadcast();        // 回调所有已注册客户端
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onResult(result);
                } catch (RemoteException e) {
                    // 客户端进程已死：RemoteCallbackList 会自动清理该回调
                }
            }
            callbacks.finishBroadcast();
            return result;
        }

        @Override
        public void registerCallback(IRemoteCallback cb) {
            callbacks.register(cb);
        }

        @Override
        public void unregisterCallback(IRemoteCallback cb) {
            callbacks.unregister(cb);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;      // 框架据此封装成 Proxy 交给客户端
    }
}
```

```xml
<!-- 跨应用调用时用 permission 限制；同应用可设为 exported=false -->
<service
    android:name=".CalcService"
    android:exported="true"
    android:permission="com.example.ipc.REMOTE_SERVICE" />
```

客户端（绑定 + 调用 + 回调 + 死亡监听）：

```java
public class ClientActivity extends AppCompatActivity {

    private ICalcService service;   // 跨进程时实际是 Proxy

    private final IRemoteCallback.Stub callback = new IRemoteCallback.Stub() {
        @Override
        public void onResult(int value) {          // 回调运行在 Binder 线程
            runOnUiThread(() -> tvResult.setText("结果：" + value));
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ICalcService.Stub.asInterface(binder);   // 同进程返回 Stub 本体
            try {
                binder.linkToDeath(deathRecipient, 0);          // 监听服务进程死亡
                service.registerCallback(callback);
                int sum = service.add(1, 2);                    // 同步 IPC：勿在主线程频繁调用
                tvResult.setText("结果：" + sum);
            } catch (RemoteException e) {                       // DeadObjectException：服务进程已死
                service = null;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;                                     // 连接断开（崩溃/被杀）
        }
    };

    private final IBinder.DeathRecipient deathRecipient = () -> service = null;

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, CalcService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (service != null) {
            try { service.unregisterCallback(callback); } catch (RemoteException ignored) { }
        }
        unbindService(conn);    // 必须 unbind，否则 ServiceConnection 泄漏
        super.onStop();
    }
}
```

**Messenger 对照**（同样的能力，简单但受限——内部就是 AIDL）：

```java
// 客户端：只能发 Message，没有返回值、没有方法调用
Messenger messenger = new Messenger(service);
Message msg = Message.obtain(null, MSG_ADD);
msg.arg1 = 1; msg.arg2 = 2;
messenger.send(msg);

// 服务端：Handler 串行处理，天然没有并发问题
Messenger server = new Messenger(new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) { /* 处理并可通过 msg.replyTo 回信 */ }
});
```

**易错点小结**：回调在 Binder 线程（切主线程更新 UI）；服务端实现要线程安全（最多 15 线程并发）；`RemoteCallbackList` 而非集合；`oneway` 不保证送达；主线程同步 IPC 卡顿；跨应用绑定查 `<queries>`、权限双向校验。

### Q31. startService / bindService 生命周期差异？前台服务的新规？ ★★

startService：onCreate → onStartCommand（多次）→ stopSelf/stopService；bindService：onCreate → onBind → IBinder 通信 → onUnbind → onDestroy（随绑定者存活）。**前台服务**：Android 14 起必须声明 `foregroundServiceType`（camera/location/mediaPlayback…）并申请对应运行时权限——**相机前台服务需要 CAMERA 类型 + 前台权限**，这正是"后台长录"在 Android 上的合法路径（对照 iOS 学习文档第 9 章的对比）。Android 15 进一步要求 use case 匹配。

### Q32. 广播的发送与注册要点？ ★

普通/有序（priority、abortBroadcast）/粘性（已废弃）/本地（LocalBroadcastManager 已废弃→ LiveData/Flow 替代）。动态注册随生命周期注销；静态注册 Android 8+ 对隐式广播大幅限制（豁免清单）。相机场景：ACTION\_CAMERA\_BUTTON、USB 设备插拔（UsbManager.ACTION\_USB\_DEVICE\_ATTACHED）是常用显式广播。

### Q33. ContentProvider 的启动时机与作用？ ★★

进程启动时 **installContentProviders 先于 Application.onCreate 完成**（attachInfo 阶段初始化），是"进程启动必经路径"——这也是 SDK 无侵入初始化（init-provider）与启动优化（延后初始化 provider）的考点。作用：跨进程结构化数据共享（Call 路径 + URI 权限临时授予 grantUriPermission）。

### Q34. App 保活有哪些手段？哪些是死路？ ★★★

合法路径：前台服务（分类型）、WorkManager 周期任务、高优先级推送通道（FCM/厂商推送）、用户白名单（电池优化豁免）。死路（被系统针对性封杀）：1 像素 Activity、双进程守护（Android 5+ 无效）、JobScheduler 高频唤醒（Doze/App Standby 限制）。追问：为什么"提权到 persistent 进程"只留给系统应用（OOM 保护 + 崩溃重启语义，滥用破坏体验）。

***

## 六、协程与 Flow

### Q35. suspend 函数的原理？什么是状态机与 Continuation？ ★★★

编译器把 suspend 函数转成 **CPS（续体传递）** 形式：插入 `Continuation` 参数，函数体按挂起点切成状态机（`smali` 层是 switch(label)）。挂起时保存 label 与局部变量到 Continuation 并返回（不阻塞线程），恢复时从 Continuation.resumeWith 带结果跳回。这是"同步写法的异步"的实现根基；对比线程阻塞：协程挂起不占用线程。

### Q36. 结构化并发是什么？Job / SupervisorJob 的行为差异？ ★★★

协程的生命周期被组织进树：父 Job cancel 级联取消子，子异常默认传播取消父（**Job**）；**SupervisorJob** 下子失败不连坐父与兄弟（VM scope 常用 `SupervisorJob() + Main`）。`coroutineScope{}` 与 `supervisorScope{}` 是函数级作用域选择。追问：GlobalScope 为什么被 lint 禁（脱离结构化、泄漏与生命周期失控）。

### Q37. Dispatchers 与线程切换的成本？withContext 为什么快？ ★★

Default（CPU 核数并行）、IO（64 线程池弹性）、Main（主线程 Handler）。`withContext(Dispatcher)` 的切换是**无锁调度**（调度器队列 + dispatch 协议），比线程池 submit + future 组合轻得多；相同 Dispatcher 的连续 withContext 甚至免切换（fast path）。追问：Main.immediate 与 Main 的差别（已在主线程时不 post，避免重排序）。

### Q38. 冷流 / 热流？StateFlow 与 SharedFlow？ ★★

Flow 冷流：collect 时才开始生产、每个收集者独立（建 map 一次次重新执行上游）；SharedFlow/StateFlow 热流：生产与收集解耦。**StateFlow**：始终持有最新值、conflate、初值必填（适合 UI 状态）；**SharedFlow**：可配 replay/extraBufferCapacity（事件流），可丢失策略。热点追问：StateFlow 重复值不发射（distinctUntilChanged 语义）。

### Q39. 协程的异常与取消怎么处理？ ★★★

异常分两类：**CancellationException** 是正常取消信号（吞掉它破坏结构化取消）；其他异常沿 Job 树向上传播，根作用域上交给 CoroutineExceptionHandler。launch 的异常直接进 handler；async 的异常在 await 时抛出（未 await 的失败 deferred 仍会传播—— 用 supervisorScope + 显式处理）。取消是协作的：挂起点抛 CancellationException，CPU 密集循环要 `isActive`/`ensureActive()` 自查；finally 里可用 `withContext(NonCancellable)` 清理。

### Q40. repeatOnLifecycle 为什么比 launchWhenStarted 好？ ★★

`lifecycleScope.launch { repeatOnLifecycle(STARTED) { collect(flow) } }`：在 STARTED 上收集、STOP 时**取消**收集器、回来重建——保证后台不消费也不泄漏中间态。launchWhenStarted 只挂起协程（流仍在生产，有状态的上游如 SharedFlow 会积压/占用）。官方因此 deprecated launchWhenX。

***

## 七、性能优化与稳定性

### Q41. 启动优化清单？Baseline Profile 是什么？ ★★★

* 阶段拆分（Perfetto 量测）：Application onCreate 三方 SDK 异步化/懒加载 → 首帧（Activity 创建/布局）→ 可交互；
* 手段：SplashScreen API 规范化、启动期禁重 IO、View 简化、按需初始化（App Startup 库）、dex 布局优化；
* **Baseline Profile**：预编译关键路径为机器码（JIT 提前化），冷启动与首帧显著提升（官方数据 \~30%）；Macrobenchmark 度量。

#### Baseline Profile 实际操作流程

前置条件：AGP 8.1+ / Android Studio Giraffe+；设备端支持 **Android 7.0（API 24）及以上**（profile 由 `androidx.profileinstaller` 在安装后落到 ART profile 目录，空闲时 `bg-dexopt` 编译）；生成 profile 需要 **root 真机 / 模拟器 / Gradle Managed Device（aosp 镜像）**——生成器要读写 ART 的 profile 目录。

**1）建生成器模块**

Studio 模板：`File > New > Module > Baseline Profile`（AGP 8.1+），或在工程里建 `:baselineprofile` 测试模块（`com.android.test`），app 模块应用 `androidx.baselineprofile` Gradle 插件。模板会自动配好 target project、release 变体与设备配置。

**2）写生成器测试（覆盖启动 + 关键用户路径）**

```kotlin
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.example.app",
        includeInStartupProfile = true,   // 标记为启动路径，进入 startup profile
        profileBlock = {
            startActivityAndWait()         // 冷启动
            device.findObject(By.text("打开相机")).click()
            device.waitForIdle()
            // 再覆盖典型路径：滚动列表、进入详情、返回等
        }
    )
}
```

要点：启动路径标注 `includeInStartupProfile = true`；非启动的关键路径可以另写 `collect`（不标注），最终合成完整 baseline profile。UI 结构大改后要重新生成。

**3）执行生成任务**

```bash
./gradlew :app:generateBaselineProfile            # 生成当前变体
./gradlew :app:generateReleaseBaselineProfile     # 指定变体
```

任务在目标设备上以 instrumentation 方式跑生成器，结束后把规则复制到源码集：

```
app/src/<variant>/generated/baselineProfiles/baseline-prof.txt
```

（AGP 8.2+ 支持该目录多文件、随变体管理；旧版本是 `src/main/baseline-prof.txt`。不要手工从 build/outputs 拷贝。）

**4）依赖与打包**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")  // 安装 profile 的运行时库
}
```

AGP 8+ 模板通常会自动加上。构建时 AGP 把 `baseline-prof.txt` 编译为二进制 `baseline.prof` / `baseline.profm`，打进 APK 的 `assets/dexopt/` 目录，随 Play/安装流程在设备上生效。

**5）验证是否生效**

```bash
# ① 确认 APK 内带 profile
unzip -l app-release.apk | grep dexopt
#   assets/dexopt/baseline.prof / baseline.profm

# ② 触发安装并看编译状态（调试用；正式包由 Play 在安装时处理）
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  com.example.app/androidx.profileinstaller.ProfileInstallReceiver
adb shell cmd package compile -m speed-profile -f com.example.app
adb shell dumpsys package dexopt | grep -A 5 "com.example.app"

# ③ 用 Macrobenchmark 的 StartupTimingMetric 对比开/关 profile 的冷启动（多次取中位数）
```

**6）接入 CI 与维护**

* AGP 8.1+ 插件提供 `automaticGenerationDuringBuild`，可在发布构建时自动生成（需 GMD/AOSP 镜像）；`saveInSrc` 等选项控制是否回写源码集；
* 每次大版本 UI/入口变化重跑生成器；混淆规则变化后**不要手改规则**，直接重新生成（规则名称要和线上混淆后的类/方法一致）；
* 二进制 profile 有 **1.5MB** 上限，满了 ART 会截断/忽略部分规则；
* profile 只加速冷路径（启动、首次进入页面）；热路径/常驻逻辑的收益有限，不要期待全面提速。

常见坑：用 debuggable 包生成（规则只对 debug 变体有意义，线上无效）；在非 root 普通用户机上生成（写不了 ART profile）；把生成产物手工复制到 `src/main`（应由插件按变体复制）；只测一次就下结论（启动受温度/后台负载影响，必须多次取中位数）。

### Q42. LeakCanary 的原理？常见泄漏点？ ★★★

原理：**Activity/Fragment/ViewModel/View/Fragment.View 销毁后**把弱引用交给 Watcher，触发 GC 后若仍可达则 dump heap（hprof），在 App 内解析**引用链**（Shortest Path to GC Roots，排除已知路径）并给出泄漏报告。常见泄漏：静态持有 Context/View、非静态内部类（Handler/Thread/AsyncTask）、未注销的监听/广播、单例持 Activity、Lottie/动画未清。治理优先改设计（生命周期作用域），不要只靠弱引用兜底。

### Q43. 掉帧/卡顿怎么监控与定位？ ★★★

* 原理：Choreographer 在 VSync 回调时统计帧间隔（FrameMetrics/JankStats）；UI 线程卡与 RenderThread 卡分开看；
* 工具：Perfetto/Systrace（主线程长任务、binder、锁等待、GC）、StrictMode（磁盘/网络主线程）、ANR trace（/data/anr，主线程栈顶）；
* 典型根因：主线程 IO、同步 binder、JSON 大对象解析、过度重绘（GPU overdraw）、GC 风暴。相机场景：预览回调线程阻塞会连锁掉帧（HandlerThread 隔离）。

### Q44. Bitmap 优化与大图加载？ ★★

分辨率匹配（inSampleSize 双压：inJustDecodeBounds 先测）、`inPreferredConfig`（RGB\_565 无透明场景）、Android 8+ 像素存 native 堆（java 对象仅壳，需注意 native 泄漏与 isRecycled）。超大图用 **BitmapRegionDecoder** 分块/手势加载。Glide/Coil 的位图复用（inBitmap 池）与三级缓存（活动缓存弱引用 + 内存 LRU + 磁盘）是标准答案扩展。

### Q45. APK 体积优化？ ★★

代码：R8 全模式 + 资源 shrink、去重复三方、ABI 拆分/APK Split 或 AAB（按设备下发）、动态交付（on-demand module）。资源：webp/AVIF、矢量图替代多密度 PNG、无用资源清理、语言资源限定。so：只留必要 ABI、`-Os` 编译、strip、压缩对齐（zipalign）。红利项：Baseline Profile 不增体积提流畅度；启动器库合并。

### Q46. ANR 的类型与排查？ ★★★

输入事件 5s、前台 Service 20s、广播前台 10s/后台 60s、ContentProvider 超时（10s publish）。排查：/data/anr trace 看主线程栈（BLOCKED 的锁持有者、binder 调用目标）、CPU/IO 占用、是否 GC 风暴；Android 11+ ANR 原因分类（Input/Service/Broadcast/Provider + "无响应原因"）。预防：主线程零 IO 零重计算、锁粒度、启动期不做同步 binder 链。

### Q47. Crash 治理体系怎么搭？ ★★

采集（UncaughtExceptionHandler + NEL/信号捕获 native crash，或接入 Breakpad/xcrash）→ 聚合（按堆栈签名聚类、Top 异常看趋势）→ 分层处置（崩溃率红线、灰度监控、热修/回滚）。重点补充：**主线程看门狗**（自己监控主线程阻塞防 ANR 前置）、OOM 专项（bitmap/内存曲线）、so 符号表归档（回溯堆栈）。

***

## 八、存储与数据

### Q48. SharedPreferences 的问题与替代？ ★★

问题：同步阻塞主线程（首次 load 全量进内存）、commit 同步写、apply 的跨进程不可靠（QueuedWork 在某些版本造成 ANR）、大数据低效。替代：**DataStore**（协程/Flow 化，Preferences 或 Proto）、**MMKV**（mmap + protobuf，跨进程支持）。追问：为什么 mmap 写入掉电安全（内核回写，但需处理文件损坏与 crc）。

### Q49. Room 的价值与原理要点？ ★★

编译期 SQL 校验（注解处理器生成实现）、LiveData/Flow 可观察查询（invalidation tracker 监听表变更）、事务 API 与迁移（Migration/自动迁移）。坑：主线程查询默认禁止（allowMainThreadQueries 别用）、Flow 查询的重放语义、索引与 @Query 匹配。

### Q50. Scoped Storage 之后，媒体文件怎么存取？ ★★★

* 共享媒体走 **MediaStore**（插入 collection + IS\_PENDING 两段提交，或 MediaStore 的 RELATIVE\_PATH）；
* 用户选择走 **Photo Picker（ ACTION\_PICK\_IMAGES）** 或 SAF（ACTION\_OPEN\_DOCUMENT，持久授权 takePersistableUriPermission）；
* 自己目录：app-specific 外存免权限。与相机系列衔接：拍照保存闭环（对照相机面试题 Q62 与 iOS 学习文档第 7 章的 PhotoKit 对照）。

### Q51. Parcelable vs Serializable？ ★

Serializable：反射 + 元数据，兼容跨平台序列化，慢且体积大；Parcelable：编译期实现Parcel 读写（手写或 @Parcelize），内存态快数倍。Intent/Bundle 传输用 Parcelable；持久化/网络用 JSON/Proto。注意 Bundle 1MB 事务限制（大对象走文件/内容提供者）。

***

## 九、Jetpack 与架构

### Q52. ViewModel 为什么旋转后不丢？它真的不泄漏吗？ ★★

旋转重建时 Activity 创建新的 ViewModelStoreOwner 前，先经 **onRetainNonConfigurationInstance** 把 ViewModelStore 挂到 NonConfigurationInstances 传递给新实例——Activity 销毁但 Store 被 ActivityThread 侧持有转交。风险：持 Context 引用（用 AndroidViewModel 的 application）；**ViewModel 在 Activity finish 时才 clear**，配合协程 viewModelScope 自动取消。

### Q53. LiveData vs StateFlow 怎么选？ ★★

LiveData：生命周期感知内置（主线程 observe）、简单 UI 态够用；局限：无操作符、主线程发值限制。StateFlow：协程生态、操作符丰富、需配 repeatOnLifecycle 手动感知生命周期；跨层（Domain/Repository）用 Flow，UI 层可用 LiveData 或 StateFlow（Compose 只有 StateFlow 路线）。追问：LiveData 背压与粘性（observe 立即收当前值）与事件流的"数据丢失/重复消费"问题（用 SharedFlow/channel 或 Event wrapper）。

### Q54. 单 Activity + Navigation 的收益与坑？ ★★

收益：统一返回栈/深链/转场（Navigation 组件图、类型安全参数 Android 声明式 DSL）；坑：超深栈内存（Dialog fragment 滥用）、动画与 compose 过渡统一、深链参数校验。相机场景多保留多 Activity（相机独立 task/affinity 合理）。

### Q55. Hilt 的原理与与 Koin 的区别？ ★★

Hilt：编译期（注解处理器生成组件图，Component 层级绑定 BuildType/生命周期，SSA/ActivityRetainedComponent 支持配置变更），错误编译期暴露；Koin：运行时 DSL 注册（灵活、无代码生成，错误运行时发现）。考点：@HiltViewModel 与 viewModelScope、@AndroidEntryPoint 的成员注入时机（onCreate 前）。

### Q56. MVVM 与 MVI 的本质区别？ ★★

MVVM：双向数据通道（状态+事件混流），灵活但状态来源多；MVI：**单向数据流 + 单一状态树**（Intent→Reducer→State，UiState 不可变 + 唯一渲染入口），可测可回放，代价是样板与状态粒度设计。实践折衷：MVI 状态 + MVVM 的命令通道（事件用 Channel/SharedFlow 而非塞进 State）。

***

## 十、Kotlin 与 Compose

### Q57. Kotlin 空安全与平台类型（!）？ ★★

编译期可空性进类型系统（?/!!/?./:?/let 组合）；**平台类型**（来自 Java 无注解签名）是"未知的可空性"——运行期裸传会通过编译期检查埋 NPE。治理：接 Java 层加 @Nullable/@NonNull 注解、边界处立即收窄为非空或可空类型。追问：lateinit 与 by lazy 的适用差异（var 注入/字段 vs 只读初始化成本延迟）。

### Q58. 扩展函数与委托属性的实现原理？ ★★

扩展函数编译为**静态方法**（第一个参数是接收者），无运行时魔法、不修改类、按静态类型分派（成员优先）；泛型扩展 + reified 保留类型。委托属性（by lazy/by Delegates.observable/自定义）编译为访问器转发到 Delegate 对象（getValue/setValue 协议）；`by lazy` 默认 SYNCHRONIZED（LazyThreadSafetyMode 可选）。

### Q59. inline / noinline / crossinline 的作用？ ★★★

inline：函数体（含 lambda）内联到调用点——消除 lambda 对象与调用开销（高阶函数库、DSL）、让 **非局部返回**（return 从外层函数返回）与 reified 泛型成为可能；noinline：某个 lambda 不内联（要存起来/传递）；crossinline：内联但禁止非局部返回（lambda 会在别的执行上下文被调）。追问：为什么所有协程库高阶 API 大量 inline（挂起函数状态机与内联的配合）。

### Q60. data class / sealed class / object 各解决什么？ ★

data class：equals/hashCode/copy/toString/componentN 生成（解构）；限制（不能继承、构造参数为主属性）。sealed：**受限继承树**（同模块穷举），when 免 else（编译器完整性检查）——状态建模首选。object：单例与伴生（companion）；匿名 object 表达式。Compose 的 UiState 设计标配：sealed interface + data class。

### Q61. Compose 的重组（recomposition）与稳定性？ ★★★

重组是"以函数为单位重新执行"：State（`mutableStateOf`）读取被记录，变更触发对应作用域重组（最小作用域原则）。**稳定性**决定跳过优化：稳定类型（不可变/@Stable 注解/基本类型）参数未变可跳过；List/lambda 默认不稳定 → 用 ImmutableList（kotlinx.collections.immutable）、@Stable/@Immutable 标注、remember 缓存。常见性能问题：在组合中做重计算（用 remember/derivedStateOf）、lambda 每次新建（remember lambda）、动画驱动的过大重组范围（defer read：状态读取下移）。

### Q62. Compose 与 View 体系怎么选？ ★

新项目 UI 主线推荐 Compose（声明式、状态驱动、动画/主题统一、与协程/Flow 原生配合）；互操作路径：ComposeView 嵌入 View 体系、AndroidView 嵌入传统控件。相机预览例外：**PreviewView（View 体系）经 AndroidView 桥接**是官方 CameraX-Compose 现行路径——Surface 的合成层不宜放进 Compose 树的渲染模型（与 Q20 的 SurfaceView/TextureView 语义一致）。

***

## 十一、网络

### Q63. OkHttp 的拦截器链机制？ ★★

请求穿过责任链：应用拦截器（addInterceptor，先于重试/缓存，只调一次）→ RetryAndFollowUp → Bridge → Cache → Connect（连接池复用、TLS 握手）→ 网络拦截器（addNetworkInterceptor，重定向/缓存命中也会走，调试用）→ CallServer。每个 RealInterceptorChain 携带 allocation 限制调用深度；HTTP/2 多路复用与连接池是复用核心。

### Q64. HTTPS 的握手与证书校验？ ★★★

TLS1.2：ClientHello → ServerHello+证书 → 密钥交换（ECDHE）→ 完成（对称密钥会话）。TLS1.3：1-RTT（甚至 0-RTT 恢复），加密套件精简。校验链：证书链到受信 CA（系统信任库）、域名匹配、有效期；**证书固定**（CertificatePinner）防中间人但要处理轮换灾难（备份 pin + 失效策略）。追问：为什么 TLS1.3 快（握手轮次减、会话恢复 PSK）。

### Q65. Retrofit 的原理？ ★

接口 + 注解 → 动态代理（Proxy.newProxyInstance）解析方法注解生成 ServiceMethod → RequestFactory 组装请求 → CallAdapter（挂起函数支持：协程适配器把 Call 转 suspend）→ Converter（Gson/Moshi/Proto 解析）。追问：为什么 suspend 接口不需要回调（代理层用 Continuation 恢复）。

***

## 十二、ART 与底层

### Q66. Dalvik 与 ART 的区别？AOT/JIT 混合编译？ ★★

Dalvik：JIT 为主（每次运行解释/编译热点）。ART（5.0+）：安装期 AOT（dex2oat 全量，安装慢）→ 7.0 起 **混合**：安装只做 profile 引导的稀疏编译，运行期 JIT + profiling，空闲时（Background dexopt）按 profile 做 AOT——Android 9 起引入 Cloud Profiles（Play 分发运行时采集的热点），其后又推出 Baseline Profiles（库/应用预置 profile）。追问：为什么"全量 AOT"被放弃（安装/升级耗时、存储膨胀、热点覆盖低）。

### Q67. ART 的 GC 特点？ ★★★

现代 ART：分代（young/old）、并发标记（Concurrent Copying，读屏障实现移动式整理 STW 极短）、Region 化堆。调优点：大对象空间（bitmap）、GC 抑制场景（游戏/相机预览用 native 内存避开 Java 堆——这也是相机 buffer 走 native 分配的原因之一）。排查：`am dumpheap`、perfetto 的 GC 轨道、`Debug.getRuntimeStat`。

### Q68. 类加载与双亲委托？ ★★

ClassLoader 树：BootClassLoader（核心库）→ PathClassLoader（APK）→ 自定义。双亲委托：先委派父加载（安全 + 共享一致性），父不可加载才自加载；破坏场景：插件化/热修（插入式 ClassLoader、反射改 parent）。追问：为什么双亲委托能防核心类被伪造（java.lang.String 只能由 Boot 加载）。

### Q69. 一次"点击图标到看到界面"中，有哪些跨进程通信？（综合串联题） ★★★

按序：Launcher→ATMS（startActivity，Binder）→ Zygote socket（fork，非 Binder）→ ActivityThread 与 system\_server（ApplicationThread Binder：生命周期调度）→ WMS（Surface 分配与窗口，IWindowSession）→ InputFlinger（InputChannel 事件，socket 对）→ SurfaceFlinger（图层提交，BLASTBufferQueue/事务）→ 渲染（GPU 驱动）。答题要点：分清 Binder/socket/共享内存三类通道各自的用途——这也是理解相机框架（Binder 控制 + 共享内存数据）的通用底座。

***

> 复习建议：Q1-Q16 与 Q28-Q34 是"必答题"（答不满是减分项）；Q17-Q27、Q35-Q47 是中级分水岭；Q48-Q69 按目标岗位侧重（应用架构 → Jetpack/Kotlin/Compose 优先，系统方向 → IPC/ART/串联题优先）。
