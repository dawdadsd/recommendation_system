最关键的点就是 `@Primary`。

在 [SupplierRuntimeConfiguration.java#L55](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/config/SupplierRuntimeConfiguration.java#L55) 这里：

```java
@Bean
@Primary
SupplierPullClient routingSupplierPullClient(...)
```

它的意思不是“这个 Bean 更高级”，而是：

“当 Spring 看到有人要注入 `SupplierPullClient` 接口，但候选实现不止一个时，默认选我。”

**为什么这里一定要有默认实现**

因为你当前容器里，`SupplierPullClient` 候选其实不止一个，而是至少 4 个：

- [MockSupplierPullClient.java#L15](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/mock/MockSupplierPullClient.java#L15) 是一个 `SupplierPullClient`
- [KingdeeErpAdapter.java#L68](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/adapter/kingdee/KingdeeErpAdapter.java#L68) 也是一个 `SupplierPullClient`
- [YonyouErpAdapter.java#L66](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/adapter/yonyou/YonyouErpAdapter.java#L66) 也是一个 `SupplierPullClient`
- [RoutingSupplierPullClient.java#L36](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/adapter/RoutingSupplierPullClient.java#L36) 也是一个 `SupplierPullClient`

所以 Spring 看到“我要一个 `SupplierPullClient`”时，会问一句：

“你到底要哪一个？”

这时候 `@Primary` 就是在回答：

“如果没人特别说明，就用路由版 `routingSupplierPullClient`。”

**为什么默认必须是 routing，而不是金蝶/用友/mock**

因为业务执行层想依赖的是“统一入口”，不是某个具体 ERP。

看 [SupplierPullExecutionApplicationService.java#L49](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/application/service/SupplierPullExecutionApplicationService.java#L49)，它只接受一个抽象的 `SupplierPullClient`：

```java
public SupplierPullExecutionApplicationService(
    SupplierConnectionRepository supplierConnectionRepository,
    SupplierPullClient supplierPullClient,
    RawDataPublisher rawDataPublisher,
    SupplierPullAuditRepository auditRepository)
```

它不应该知道：

- 当前 supplier 是金蝶还是用友
- 是否要走 fallback mock
- 是否要做 circuit breaker
- 是否要做 per-supplier 并发隔离

这些运行时决策，都应该收口在 [RoutingSupplierPullClient.java#L94](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/adapter/RoutingSupplierPullClient.java#L94)。

也就是说：

- `KingdeeErpAdapter`、`YonyouErpAdapter` 是“具体执行者”
- `RoutingSupplierPullClient` 是“总入口 / 调度者”
- `SupplierPullExecutionApplicationService` 应该只拿到“总入口”

所以把 router 标成 `@Primary`，本质上是在表达一个设计意图：

“系统默认的 `SupplierPullClient`，就是这个路由器。”

**如果不加 `@Primary`，会发生什么**

严格说，有两种情况。

第一种，通用情况：Spring 可能直接报错。

因为多个 Bean 都能匹配 `SupplierPullClient`，可能出现：

`NoUniqueBeanDefinitionException`

也就是：
“我找到多个实现，不知道注入哪个。”

第二种，你这个项目当前代码里“可能暂时还能跑”。

因为在 [SupplierRuntimeConfiguration.java#L72](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/config/SupplierRuntimeConfiguration.java#L72) 这里，方法参数名恰好也叫 `routingSupplierPullClient`：

```java
SupplierPullExecutionApplicationService supplierPullExecutionApplicationService(
    SupplierConnectionRepository supplierConnectionRepository,
    SupplierPullClient routingSupplierPullClient,
    RawDataPublisher rawDataPublisher,
    SupplierPullAuditRepository auditRepository)
```

Spring 在某些情况下会按参数名去匹配同名 Bean，所以它“可能”仍然会选中这个 Bean。

但这很脆弱，原因有两个：

- 你一旦把参数名改成普通的 `supplierPullClient`，这个隐式匹配就没了
- 以后别的类再注入 `SupplierPullClient` 时，未必也会起同样的名字

所以：

- `按名字碰巧注入成功` 是隐式行为
- `@Primary` 是显式声明系统默认实现

后者更稳，也更容易让后来的人看懂。

**这里还有一个容易误解的点**

代码注释里提到让 `SupplierPullTaskConsumer` 注入路由版，但更准确地说，不是直接给它注入。

看 [SupplierPullTaskConsumer.java#L24](C:/Users/admin/Desktop/recommendation_system/example/src/main/java/xiaowu/example/supplieretl/infrastructure/kafka/consumer/SupplierPullTaskConsumer.java#L24)，它实际注入的是：

- `SupplierPullExecutionApplicationService`
- `SupplierWorkerProperties`
- `ObjectMapper`

真正需要 `SupplierPullClient` 的，是 `SupplierPullExecutionApplicationService`。
所以 `@Primary` 实际上是在保证：

“执行服务拿到的默认 `SupplierPullClient` 是 routing 版。”

**为什么不用 `@Qualifier`**

也可以用，比如：

```java
SupplierPullExecutionApplicationService(
    @Qualifier("routingSupplierPullClient") SupplierPullClient supplierPullClient,
    ...
)
```

但这适合“少数地方特指某个实现”。

你这里的设计明显是：

“整个系统默认就该用 routing 版。”

这种情况下，`@Primary` 更合适，因为以后别的地方只要注入 `SupplierPullClient`，默认就会拿到 router，不用到处写 `@Qualifier`。

**你可以把它记成一句话**

`@Primary` = 给接口指定“系统默认实现”。

在你这个模块里：

- 接口：`SupplierPullClient`
- 默认实现：`RoutingSupplierPullClient`
- 具体实现：`KingdeeErpAdapter` / `YonyouErpAdapter` / `MockSupplierPullClient`

如果你愿意，我下一步可以继续给你讲这个方法本身为什么参数要写成：

`KingdeeErpAdapter + YonyouErpAdapter + MockSupplierPullClient`

而不是也全都写成 `SupplierPullClient`。这也是 Spring 装配里一个很关键的点。
