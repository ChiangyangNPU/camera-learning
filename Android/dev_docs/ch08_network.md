# 第 8 章 网络与序列化

> 材料分层：A = 官方网络文档，B = OkHttp/Retrofit 通行知识（开源项目文档）。↔ 通用题：网络板块。

## 8.1 栈总览

| 层 | 事实标准 | 说明 |
|---|---|---|
| HTTP 客户端 | **OkHttp** | 连接管理/拦截器/HTTP2 |
| 接口声明 | **Retrofit** | 注解→动态代理→OkHttp |
| 序列化 | kotlinx.serialization / Moshi | Gson 反射坑多（8.4） |
| 图片 | Coil / Glide | 协程优先选 Coil |

 HttpURLConnection 仍在官方文档（兼容用），新项目一律 OkHttp+Retrofit（B 层）。

## 8.2 OkHttp 原理

**拦截器责任链**（面试核心）：请求依次穿过拦截器链，响应逆程回来。内置顺序：

```text
addInterceptor(用户应用层) → RetryAndFollowUp(重试重定向) → Bridge(补头/解压 gzip)
→ Cache → ConnectInterceptor(建连) → [networkInterceptors(用户网络层)] → CallServer(读写socket)
```

- **应用拦截器 vs 网络拦截器**：前者只调一次（含重定向后的最终响应）、拿不到中间过程；后者每次真实连接都调、可观察重定向与缓存命中（面试高频区别）；
- 连接池默认 5 空闲连接、keep-alive 5 分钟；HTTP/2 复用单连接多路复用；
- 超时四件套：callTimeout（整次）/ connectTimeout / readTimeout / writeTimeout；DNS 可换 `Dns` 接口（HttpDNS 落点）。

## 8.3 Retrofit 原理

`Retrofit.create(Api::class.java)` 返回**动态代理**：调用注解方法 → InvocationHandler 解析注解成 ServiceMethod（请求模板）→ 组装 OkHttp Call。挂起函数支持：Kotlin 扩展 `awaitResponse` 把 Call 包装成 suspend（成功 resume body，失败 resumeWithException）——**没有 magic，就是回调转协程**（B 层）。

常用注解：`@GET/@POST/@Path/@Query/@Field/@Body/@Header/@Url`；`@Streaming` 大文件不整体载入内存。

## 8.4 序列化选型

| 方案 | 机制 | Kotlin 兼容 |
|---|---|---|
| **kotlinx.serialization** | 编译期插件，无反射 | ✅ 默认值/可空正确（推荐） |
| Moshi | codegen（KSP）或反射 | 基本正确 |
| Gson | 运行时反射 | ❌ 非空字段可被 null 注入、默认值丢失、data class 组件不用 |

Gson + Kotlin 的高危示例：服务端漏字段 → 反射绕过构造器 → `val name: String` 实际为 null → 运行时 NPE 而非反序列化失败。↔ 通用题"Gson 坑"。

## 8.5 HTTPS 与证书固定

- 系统校验证书链 + 主机名；中间人风险场景（金融类）用 **CertificatePinner**：

```kotlin
val pinner = CertificatePinner.Builder()
    .add("api.example.com", "sha256/AAAA...主证书公钥")
    .add("api.example.com", "sha256/BBBB...备用公钥")   // 必须留备份！
    .build()
```

- 固定的运维代价：证书轮换期**新旧公钥双 pin 过渡**，单 pin 轮换会全线断网（B 层教训）。

## 8.6 网络安全配置（A 层）

`res/xml/network_security_config.xml` + manifest 引用（targetSdk 28+ 默认禁明文 HTTP）：

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">   <!-- 仅调试包/指定域名 -->
        <domain includeSubdomains="true">10.0.0.3</domain>
    </domain-config>
    <debug-overrides>
        <trust-anchors><certificates src="user"/></trust-anchors>  <!-- 允许抓包工具证书 -->
    </debug-overrides>
</network-security-config>
```

## 8.7 工程实践清单

- 重试：指数退避 + 抖动（1s/2s/4s±随机），只对幂等请求自动重试（POST 幂等需业务侧幂等键）；
- Token：OkHttp `Authenticator` 同步刷新 401（需防并发多刷——单飞 + 缓存互斥）；拦截器统一注入头；
- 弱网：分级超时（连接 5s / 读 15s）、请求合并、离线缓存策略（stale-while-revalidate 语义）；
- 抓包：debug 变体允许 user 证书 + release 关闭；生产排查用服务端日志与线上网关指标。

## 8.8 图片加载对比

| | Coil | Glide |
|---|---|---|
| API | 协程/Compose First（`AsyncImage`） | 传统流式 |
| 体积 | 小（基于 OkHttp） | 大而全 |
| 成熟度 | 较新 | 久经考验、生命周期整合深 |

↔ 通用题：网络板块全部。
