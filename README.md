# Edge Fleet Orchestrator (Java bootstrap)

一套边缘设备集群的**期望状态编排**与**上报同步**能力的本地可运行实现。用进程内的
“设备会话（DeviceSession）+ 上报通道（DeviceSimulator）”模拟真实边缘网关，
不依赖任何外部服务、真实凭据或不可访问资源。

## 1. 运行环境与本地验证

- JDK 21+、Maven 3.9+（仅需 Maven Central 可解析依赖）。
- 全部数据保存在内存中，时钟可被测试替换为固定/可控时钟，验证完全可重复。

```bash
mvn test                 # 运行全部单元测试 + HTTP 集成测试（含关键判定日志）
mvn spring-boot:run      # 本地启动（端口见 application.properties，默认随机端口）
```

也可以使用脚本完成一轮端到端手工验证（启动应用后另开终端执行）：

```bash
scripts/local-e2e-demo.sh <port>
```

### 可配置策略参数（application.properties）

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `edge.fleet.heartbeat-ttl` | `30s` | 距最近一次**被接受**上报超过该时长，设备推导为 OFFLINE |
| `edge.fleet.max-report-backlog` | `60s` | 早于服务端当前时间该窗口的上报判为过旧并拒绝 |
| `edge.fleet.max-clock-skew` | `5s` | 晚于服务端当前时间该偏移的上报判为未来时钟并拒绝 |

## 2. 状态模型

### 2.1 设备存活（LivenessState）

存活状态不单独存储，而是由“最近一次**被接受**的上报时间 + TTL”实时推导：

- 从未上报，或 `now > lastAcceptedReportAt + heartbeatTtl` → `OFFLINE`；
- 否则为 `ONLINE`。

被拒绝或被忽略的上报**不会**刷新该时间点，因此无法为设备“续命”，也不会覆盖已接受的更新状态。

### 2.2 上报处理结论（ReportDisposition，原因可区分）

按如下顺序判定，命中即返回，且都不产生续期副作用：

1. `REJECTED_UNREGISTERED`：设备未注册；
2. `IGNORED_DUPLICATE`：序列号与最近已接受上报相同（重复重放）；
3. `REJECTED_FUTURE_SKEW`：时间戳晚于“服务端当前时间 + skew”；
4. `REJECTED_TIMESTAMP_TOO_OLD`：时间戳早于“服务端当前时间 - backlog”；
5. `REJECTED_TIMESTAMP_REGRESSED`：通过新鲜度窗口，但时间戳/序列号早于或等于最近已接受上报（乱序、回退）；
6. `ACCEPTED`：更新 `lastAcceptedReportAt / lastAcceptedSeq`，设备保持/变为 ONLINE。

> 乱序结论只取决于“已接受的最新上报”，与包的到达顺序无关：同一批乱序包无论怎么到达，最终接受点唯一。

### 2.3 期望状态修订（RevisionState）与下发结果（DispatchOutcome）

每次下发产生一条**单调递增**的 revision，生命周期：

```
PENDING ──► APPLIED            （送达且设备执行成功）
        ├─► REJECTED           （设备拒绝配置 / 兼容门拒绝，终态）
        ├─► FAILED             （设备执行失败，终态）
        ├─► SUPERSEDED         （被更新的下发取代，终态）
        └─► REVOKED            （撤销，终态）
```

送达结果（DispatchOutcome）把失败细分为可区分的三类：

| 结果 | 含义 | 修订状态 |
| --- | --- | --- |
| `APPLIED` / `ALREADY_APPLIED` | 首次应用 / 重复重放幂等命中 | `APPLIED`（终态，不重复应用） |
| `NOT_DELIVERED` | 通道不可达（设备离线） | 保持 `PENDING`，可稍后 reconcile 重试 |
| `REJECTED_BY_DEVICE` | 送达成功但设备拒绝 | `REJECTED`（终态） |
| `EXECUTION_FAILED` | 设备尝试执行但失败 | `FAILED`（终态） |
| `INCOMPATIBLE` | 不在兼容范围 | `REJECTED`（终态，错误码 `INCOMPATIBLE`） |
| `SUPERSEDED` / `REVOKED` | 被取代 / 已撤销 | 对应终态，再 reconcile 也不会重新应用 |

## 3. 收敛规则（并发正确性）

- **设备级串行化**：注册/查询、上报、下发、reconcile、撤销全部在同一台设备的聚合实例锁内执行；
  模拟设备通道 `DeviceSession.sendDesired` 自身也是串行的，杜绝 revision 与配置内容撕裂。
- **修订号单调**：revision 在锁内按实际取得顺序分配。并发下发产生不同修订号；
  最终唯一可达状态 = **修订号最大的那次下发所携带的配置**（与线程调度顺序无关，可解释）。
- **旧 PENDING 立即失效**：每次新下发会把此前仍处于 PENDING 的修订置为 `SUPERSEDED`。
- **同一修订最多应用一次**：已 `APPLIED` 的修订重复 reconcile 返回 `ALREADY_APPLIED`，无设备侧副作用；
  `REJECTED / FAILED / SUPERSEDED / REVOKED` 等终态同样不可重新应用。
- **已撤销不得复活**：撤销仅对 PENDING 生效；设备恢复在线后再 reconcile，已撤销修订仍为 `REVOKED`。
- **未送达可重试**：NOT_DELIVERED 不是终态，设备恢复后 reconcile 同一条修订即可收敛到 APPLIED。

## 4. 兼容范围（CapabilityRange）

- 固件版本需落在闭区间 `[minFirmware, maxFirmware]`（数值化语义版本比较，如 `1.10 > 1.2`）；
- `requiredCapability` 非空时，设备能力集合必须包含该能力；
- 不满足时**明确拒绝**并返回差距说明（低于下限 / 高于上限 / 缺少能力），
  注册与下发两个入口都执行该判定，不做“按旧语义静默放行”。

## 5. 归属方隔离与错误码

所有读写都携带 `owner`，结论用稳定错误码区分：

| 错误码 | HTTP | 语义 |
| --- | --- | --- |
| `NOT_FOUND` | 404 | 设备不存在 |
| `REVISION_NOT_FOUND` | 404 | 设备存在但修订不存在 |
| `FORBIDDEN_OWNER` | 403 | 跨归属方越权（与“不存在”可明确区分） |
| `INCOMPATIBLE` | 422 | 版本/能力不在兼容范围 |
| `DEVICE_ALREADY_EXISTS` | 400 | 重复注册 |
| `INVALID_REQUEST` | 400 | 请求格式问题 |

越权的下发不会创建修订；设备自身的上报通道不需要 owner，未注册上报返回
`REJECTED_UNREGISTERED`（HTTP 200 + disposition 字段），不泄露归属信息。

## 6. HTTP 接口一览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/fleet/devices` | 注册设备（含固件、能力、可接受范围） |
| `GET  /api/fleet/devices/{id}?owner=` | 查询单设备（含存活与最新修订） |
| `GET  /api/fleet/devices?owner=` | 按归属方列出设备 |
| `POST /api/fleet/devices/{id}/reports` | 设备周期上报 `{seq, reportTime(ISO-8601)}` |
| `POST /api/fleet/devices/{id}/desired` | 下发期望状态（创建新修订并立即尝试收敛） |
| `POST /api/fleet/devices/{id}/revisions/{rev}/reconcile` | 对既有修订重放/重试收敛 |
| `POST /api/fleet/devices/{id}/revisions/{rev}/revoke` | 撤销 PENDING 修订（终态幂等） |
| `GET  /api/fleet/devices/{id}/revisions?owner=` | 修订历史（审计） |

设备故障通过 `DeviceSimulator`/`DeviceSession` 注入（`NONE / OFFLINE / REJECT_CONFIG / EXEC_FAIL`），
测试中直接使用，模拟真实网关的不可达、拒绝与执行失败。

## 7. 测试覆盖

| 测试类 | 覆盖点 |
| --- | --- |
| `VersionAndRangeTest` | 版本数值比较、闭区间边界、三类兼容差距说明 |
| `ReportLivenessTest` | TTL 过期、乱序、重复、时间戳回退、过旧、未来偏移、未注册上报 |
| `FleetConvergenceTest` | 重复/并发下发唯一收敛、各修订仅应用一次、NOT_DELIVERED 恢复、拒绝/失败区分、撤销不复活（含 16 线程并发压力断言） |
| `AuthzAndCompatibilityTest` | NOT_FOUND vs FORBIDDEN_OWNER、越权不产生修订、INCOMPATIBLE 终态 |
| `FleetHttpIntegrationTest` | 全入口端到端：注册→上报→乱序拒绝→下发→幂等重放→越权拒绝，错误码与状态结论一致 |

所有测试日志均打印关键输入（设备、序列号、时间戳、修订号、配置）与判定依据
（对比基准、窗口、归属方、兼容差距），结论可解释、可追溯。
