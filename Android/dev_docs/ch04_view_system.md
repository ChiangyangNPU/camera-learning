# 第 4 章 View 体系

> 材料分层：A = 官方自定义 View/性能文档，B = 通行源码知识。↔ 通用题：View 板块（含 Q24~Q27 自定义 View 专题）；↔ 源码链路文档第 5 章（queueBuffer→屏幕）、Trace 文档第 1 章（渲染帧模型）。

## 4.1 从窗口到 View：三大流程的入口

调用链（B 层，源码级）：`ActivityThread.handleResumeActivity` → WindowManager.addView → **ViewRootImpl** 创建 → `performTraversals()` 依次执行：

```text
performTraversals()
 ├── performMeasure()  ← View.measure → onMeasure()          （自顶向下量尺寸）
 ├── performLayout()   ← View.layout → onLayout()            （定位置）
 └── performDraw()     ← View.draw → onDraw()  → Surface 解锁提交给 SurfaceFlinger
```

- DecorView 是根 View；ViewRootImpl 是 View 树与 WMS/SurfaceFlinger 之间的桥梁（每窗口一个）；
- measure/layout/draw 必须在主线程（有屏障保护优先级，↔ 第 3.4 节）。

## 4.2 MeasureSpec 与量传递

三种测量模式：

| 模式 | 含义 | 对应 LayoutParams |
|---|---|---|
| EXACTLY | 精确值 | match_parent / 具体dp |
| AT_MOST | 不超过给定值 | wrap_content |
| UNSPECIFIED | 不约束（容器自定） | ScrollView 对子 View 等 |

父子传递规则（`getChildMeasureSpec` 伪码结论，B 层）：

- 父 EXACTLY + 子具体值 → EXACTLY(子值)；父 EXACTLY + 子 match_parent → EXACTLY(父尺寸)；父 EXACTLY + 子 wrap_content → AT_MOST(父尺寸)；
- 父 AT_MOST + 子 wrap_content → AT_MOST(父尺寸)；其余同理递推。

> **自定义 View 经典坑**：直接在 onMeasure 用 `MeasureSpec.getSize()` 而不处理 wrap_content，会表现为撑满父容器——正确做法是 AT_MOST 分支自己算 content 尺寸再 `setMeasuredDimension`（↔ 通用题 Q25）。onMeasure 可能被调用多次（如 ListView 量两遍），别在里面做副作用。

## 4.3 layout / draw / 刷新 API 对比

draw 过程七步（View.draw 源码顺序）：绘制背景 → 保存图层（可选）→ **onDraw 内容** → **dispatchDraw 子 View** → 淡入渐变（可关）→ 滚动条/边缘效果 → 装饰（前景）。纯自定义无子 View 时 `setWillNotDraw(true)` 跳过 onDraw 优化。

| API | 触发 | 是否重测重排 |
|---|---|---|
| invalidate | 仅重绘（draw） | 否 |
| requestLayout | measure + layout + draw | 是 |
| postInvalidate | 非线程安全的 invalidate（可子线程） | 否 |

## 4.4 事件分发（面试核心）

三个方法职责：

| 方法 | 归属 | 作用 |
|---|---|---|
| dispatchTouchEvent | 所有 View | 事件路由总入口 |
| onInterceptTouchEvent | 仅 ViewGroup | 父级决定要不要拦截 |
| onTouchEvent | 所有 View | 真正消费（返回 true 表示接管） |

分发伪码规则（B 层通行结论）：

```text
父 dispatch → 问 onIntercept? 
   ├─ 拦截 → 父 onTouchEvent
   └─ 不拦截 → 按 Z 序/绘制序**逆序**问子 dispatch（点击的子优先）
        └─ 子不消费 → 冒泡回父 onTouchEvent
规则1：ACTION_DOWN 没人消费 → 后续 MOVE/UP 整串不再下发给子
规则2：子消费了 DOWN 后，父可在 MOVE 阶段中途拦截（子收到 CANCEL）
规则3：onTouch(listener) 优先于 onTouchEvent；onClick 在 UP 中触发
```

滑动冲突两法（↔ 通用题 Q23）：

- **外部拦截法**（推荐）：父容器 onInterceptTouchEvent 里按方向/位移规则决定拦截——代码集中在父，逻辑清晰；
- **内部拦截法**：子 View 在 dispatch 里调 `parent.requestDisallowInterceptTouchEvent(true)` 争抢，配合父的规则——适合子 View 需要自主决定的场景。

## 4.5 刷新机制：VSync 与 Choreographer

- 显示以 **VSync（60Hz 下 16.67ms 一拍）**为节拍；Choreographer 在主线程接收帧回调，依次执行 input → animation → traversal（measure/layout/draw）四大回调阶段；
- UI 有更新 → `ViewRootImpl.scheduleTraversals()` 插同步屏障 + `postCallback` 等 VSync → 到点执行 performTraversals → RenderThread 绘制 → queueBuffer（↔ 源码链路文档 5.4.3：VSYNC-app/SF_VSYNC/DispSync 三相位）；
- 掉帧 = 帧工作超过 16.67ms 预算（↔ 性能文档 ch4 帧预算分账算例）。

## 4.6 RecyclerView 缓存四级

| 级别 | 容器 | 是否需重绑 |
|---|---|---|
| 1 | AttachedScrap / ChangedScrap（屏幕内暂存） | 否（布局期间临时） |
| 2 | CachedViews（默认 2 个，按 position 命中） | 否（可直接复用） |
| 3 | ViewCacheExtension | 自定义（一般不用） |
| 4 | RecycledViewPool（按 viewType，默认各 5） | 是（需重新 bind） |

- 未命中 → `Adapter.onCreateViewHolder` 创建；
- 局部刷新：`notifyItemChanged(pos, payload)` + 三参 `onBindViewHolder` 只更新变化子项（B 层）；
- 列表 diff 用 `DiffUtil`/`ListAdapter`（后台线程算差异 + 自动动画）。↔ 通用题"RecyclerView 缓存机制"。

## 4.7 自定义 View 检查清单

构造器齐全（含 4 参支持 XML style）→ 读 TypedArray 后 `recycle()` → 处理 padding → 处理 wrap_content（4.2）→ onDraw 不建对象/不分配 → 触摸处理考虑父拦截 → 保存状态实现 `onSaveInstanceState`（Parcelable/SaveState）→ 无障碍（contentDescription/AccessibilityNode）→ 硬件加速兼容（避免 `canvas.clipPath` 等 API 级关黑名单）。（↔ 通用题 Q24~Q27 有展开）

## 4.8 Surface 家族（相机场景衔接）

| 控件 | 渲染层 | 特点 |
|---|---|---|
| SurfaceView | 独立 Window 层（合成器直取） | 低延迟；位置动画受限（黑块问题） |
| TextureView | View 层内（GPU 纹理） | 可普通 View 变换；多一次 GPU 拷贝，延迟略高；**自动补偿 sensor 旋转**（↔ Camera2Demo 实测经验） |
| ImageReader | 非 UI（CPU 可读缓冲） | 拍照/分析流用 |
| PreviewView（CameraX） | 自动选 SurfaceView/TextureView | PERFORMANCE 模式走硬件 overlay（↔ 性能文档 ch3） |

↔ 通用题：View 板块全部、性能板块"渲染优化"。
