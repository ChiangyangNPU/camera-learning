# 第 6 章 内存与带宽

> 本文为分章源文件，供单章阅读；若与主文档不一致，以主文档最新版为准。
>
> 相机 App 的内存大头不是 Java 对象，是**像素缓冲**；它的约束也不只 OOM，还有带宽（DDR/MIPI/ISP 吞吐）与缓冲池阻塞（不归还 = 整链卡死）。本章讲估算模型、缓冲策略、相机特有泄漏的检测、带宽评估与低内存设备降档。

## 6.1 内存估算模型：先算后配

### 6.1.1 单帧大小

```
单帧字节 = 宽 × 高 × 每像素字节
  YUV_420_888 ≈ 1.5 B/px    RGBA_8888 ≈ 4 B/px    RAW16 ≈ 2 B/px    PRIV（不落 App）≈ 与实现相关
```

常用速查（B 层量级）：

| 分辨率 | YUV420 | RAW16 | RGBA |
|---|---|---|---|
| 1080p（1920×1080，2.1MP） | ~3.1MB | ~4.1MB | ~8.3MB |
| 12MP（4000×3000） | ~18MB | ~24MB | ~48MB |
| 48MP（8000×6000） | ~72MB | ~96MB | ~192MB |

### 6.1.2 峰值公式

```
峰值 ≈ Σ(每流 maxBuffers × 单帧)        // 相机缓冲（ImageReader + 各 Surface 的 buffer 池）
     + GL 纹理/FBO                      // 处理链（每个 pass 的中间纹理按同公式算）
     + 编码器输入/输出缓冲               // 录像时
     + App 后处理位图                    // 解码后的 Bitmap 同样按像素公式算
```

**算例**（一个典型配置）：预览 1080p×3 缓冲（9MB）+ 12MP JPEG ImageReader maxImages=3（54MB）+ 640×480 分析流×4（1.7MB）+ GL 三 pass 中间纹理 1080p×2（17MB）+ 编码器缓冲（~20MB）≈ **100MB 级**；切到 48MP 档拍照（maxImages=3）瞬间冲到 **~280MB**。这解释了为什么"连拍几张 48MP 后低端机必崩"。

### 6.1.3 工程用法

预算驱动配置（反推），而不是配置后实测碰运气：

```kotlin
val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
val budget = if (am.isLowRamDevice) memInfo.availMem / 8 else memInfo.availMem / 4
// 用 budget 反推：连拍深度 N ≤ budget/单帧；48MP 档仅在非低内存设备开放
```

## 6.2 缓冲策略：maxImages 与消费纪律

### 6.2.1 maxImages 的权衡

| 取值 | 效果 | 适用 |
|---|---|---|
| 1~2 | 省内存；"上一张没消费完、下一张已到"时阻塞/丢帧 | 低频拍照（消费快：拿到即复制即 close） |
| N（连拍深度） | 连拍不阻塞 | burst；慢消费（写盘慢/后处理重） |

原则一句话：**按消费速率设**。消费路径若做到"回调里复制字节后立即 close"（下述骨架），2 就够日常；把 maxImages 当连拍深度的"粮仓"是一种用法，但要用 6.1 的公式先算内存。

### 6.2.2 消费纪律（相机内存问题的第一名）

```java
reader.setOnImageAvailableListener(r -> {
    Image image = r.acquireLatestImage();
    if (image == null) return;
    byte[] jpeg;
    try {
        ByteBuffer buf = image.getPlanes()[0].getBuffer();
        jpeg = new byte[buf.remaining()];
        buf.get(jpeg);                    // ① 先把需要的数据复制出来
    } finally {
        image.close();                    // ② 无论成败立即归还——铁律
    }
    saveAsync(jpeg);                      // ③ 慢操作（写盘/编码）在 close 之后
}, handler);
```

为什么铁律：ImageReader 的缓冲池是**有限池**（maxImages 即上限），`close()` 才是"归还给生产方复用"的动作；不归还，池耗尽后 HAL 无缓冲可写——后续拍照阻塞、预览甚至卡死。这不是 GC 意义上的泄漏（对象会被回收，但缓冲没还），所以 LeakCanary 完全看不到。

### 6.2.3 系统内存回调的响应

`onTrimMemory(level)`（A 层，[内存管理](https://developer.android.com/topic/performance/memory)）按级别释放：

| 级别 | 相机 App 应释放什么 |
|---|---|
| `TRIM_MEMORY_UI_HIDDEN` | 滤镜 LUT 纹理、非当前模式的资源 |
| `TRIM_MEMORY_RUNNING_LOW` | 缩略图缓存、后处理 Bitmap 池 |
| `TRIM_MEMORY_RUNNING_CRITICAL` 以上 | 降级到基础预览（关效果流）、缩小 ImageReader |

相机 App 常忽略这个回调，是低端机被杀率高的直接原因之一——系统已经警告了，App 还占着几百 MB 像素缓冲。

## 6.3 相机特有的"泄漏"与检测

| 场景 | 机制 | 检测方法 |
|---|---|---|
| Image 未 close | 缓冲池耗尽（6.2.2） | 进/出拍照循环 10 次：逐渐卡死/丢帧即为铁证 |
| SurfaceTexture/Surface 未释放 | native 缓冲累积 + 重建黑屏 | 页面进出循环看 native 增长曲线 |
| 会话/设备未 close | 持有整条相机管道直到被抢占 | `adb shell dumpsys media.camera` 看本包 client 残留 |
| 相机回调持 Activity | 回调线程生命周期 > 页面（Handler 泄漏的相机版） | LeakCanary（这个它能看到） |
| GL 纹理/FBO 未删 | native 显存累积 | `glDeleteTextures` 配对检查；native 曲线阶梯增长 |

**检测脚本**（B 层实践）：

```bash
# 进/出相机页循环（脚本驱动），每轮采样：
for i in $(seq 1 10); do
  adb shell input keyevent KEYCODE_MOVE_HOME; sleep 1
  adb shell am start -n com.example.camera/.MainActivity; sleep 3
  adb shell dumpsys meminfo com.example.camera | grep -E "TOTAL|Native|Graphics"
done
# 判读：TOTAL/Native/Graphics 单调阶梯上升 = 泄漏；回到基线 = 健康
```

关键认知：**LeakCanary 对 native 缓冲无感**（它查 Java 堆引用链）——像素缓冲泄漏只能靠内存曲线 + `dumpsys meminfo` 的 Graphics/Native 分项 + Perfetto 内存轨道对齐时间点。上线前把"进出循环"做成自动化用例（第 8 章 CI）。

## 6.4 多路流带宽评估

### 6.4.1 带宽公式与算例

```
每流带宽 = 宽 × 高 × 每像素字节 × 帧率
```

| 配置 | 带宽 |
|---|---|
| 1080p30 预览（YUV） | 1920×1080×1.5×30 ≈ **93MB/s** |
| 1080p30 预览（PRIV 直通） | 不经过 App/DDr 路径（合成走叠层），App 侧带宽 ≈ 0 |
| 640×480@15 分析流（YUV） | ≈ **6.9MB/s** |
| 4K30 录像（到编码器） | 3840×2160×1.5×30 ≈ **373MB/s** |

约束点链：MIPI（sensor→SoC）→ ISP 吞吐 → DDR 带宽 → 编码器。多流并存时**求和**逼近上限——"预览 + 4K 录像 + 全分辨率分析流"的组合在多数平台上超限，表现就是配流失败或帧率骤降。

### 6.4.2 工程判断清单

- 分析流用**最小可用尺寸与帧率**（CameraX 用 `ResolutionSelector` 压到 640 级；背压 `STRATEGY_KEEP_ONLY_LATEST`——A 层 [ImageAnalysis 文档](https://developer.android.com/media/camera/camerax/analyze)，消费慢时丢旧不阻塞）；
- 预览走 **PRIV 直通**（不落 YUV、不经 App 手）——省的是整条最大流的 App 侧带宽；
- 不用的流**及时移除**（重建会话）而不是"配着不用"——配着的流就占带宽与缓冲；
- 组合上线前用 `isStreamCombinationSupported`（API 29+）/ `isSessionConfigurationSupported`（API 28+）预检——HAL 拒绝的组合本身就是带宽/资源超限的信号（A 层）；
- 多摄并发（`getConcurrentCameraIds`）的带宽更是翻倍计算，按官方保证组合走（第《学习文档》）。

## 6.5 内存预算实践

- **峰值红线分档**（第 1 章预算表的内存行）：如中端机 ≤450MB，CI 长跑用例（预览 10min + 连拍 + 录像）采集峰值，版本对比超线阻塞；
- **低内存设备自动降档**：`isLowRamDevice` / `availMem` 驱动——降连拍深度、关 48MP 档、分析流降频、滤镜减 pass（与第 4 章降级阶梯复用同一套机制）；
- **Bitmap 处理纪律**（A 层 [Bitmap 管理](https://developer.android.com/topic/performance/graphics/manage-bitmap)）：后处理大图用 `inSampleSize` 降采样解码、`inBitmap` 复用位图池、及时 `recycle()`（老版本）；Android 8+ 像素在 native 堆，但仍占总量；
- **版本纪律**：新功能（新滤镜/新 AI）合入前后峰值对比——多数内存事故是"每个功能各加 50MB，谁都没超标，合起来爆了"。

---

> 来源与延伸：[内存管理](https://developer.android.com/topic/performance/memory)、[Bitmap 管理](https://developer.android.com/topic/performance/graphics/manage-bitmap)、[onTrimMemory/ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2)、[ImageAnalysis 背压](https://developer.android.com/media/camera/camerax/analyze)、[StreamConfigurationMap](https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap)；峰值公式/速查表/降档策略为通行实践（B 层）。多路流在算法链路中的带宽权衡另见《算法集成文档》第 4 章；内存曲线在 Perfetto 中的对齐用法见《Trace分析文档》第 6 章。
