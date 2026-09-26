# Edge Fleet Orchestrator（边缘设备集群期望状态编排，本地模拟版）

用**本地设备会话与上报通道**模拟真实边缘网关，实现边缘设备集群的：

1. 设备注册与存活状态维护（周期上报证明存活，乱序/过期/回退时间戳可区分地拒绝或忽略）；
2. 期望状态下发与收敛（版本单调、并发唯一、同版本不重复应用、已撤销变更不复活，失败三分类）；
3. 能力/固件版本兼容判定与归属方（owner）隔离（不兼容明确拒绝，越权与目标不存在可区分）。

全部能力为内存实现 + 可控时钟，**不依赖任何外部服务、真实凭据或不可访问资源**。

---

## 1. 状态模型

### 1.1 设备存活状态 `DeviceStatus`

| 状态 | 进入条件 |
| --- | --- |
| `ONLINE` | 收到一次**被接受**（`ACCEPTED`）的上报 |
| `OFFLINE` | 从未上报，或距最近一次被接受上报（`lastSeenAt`）超过心跳 TTL（`edge.heartbeat-ttl`，默认 30s） |

> 注意：存活以**平台观测时间**（服务端时钟）为准，而非设备上报的时间戳，防止设备时钟回拨伪造保活。
> 过期判定在两处发生：`LivenessService.sweep()` 全量扫描，以及查询设备时的 `observe()` 按需评估。

### 1.2 上报受理结论 `ReportVerdict`

| 结论 | 类别 | 含义与判定依据 |
| --- | --- | --- |
| `ACCEPTED` | 接受 | 时间戳合法且单调（≥ 上一次被接受的时间戳），刷新 `lastReportTimestamp / lastSeenAt / ONLINE` |
| `REJECTED_MISSING_TIMESTAMP` | **拒绝** | 时间戳为 null |
| `REJECTED_FUTURE_TIMESTAMP` | **拒绝** | 时间戳超前服务端超过 5s 容忍窗口（时钟不可信） |
| `IGNORED_DUPLICATE` | **忽略** | 时间戳等于上一次被接受的上报（重复投递） |
| `IGNORED_STALE_TIMESTAMP` | **忽略** | 时间戳早于上一次被接受的上报（乱序/时钟回退） |

**拒绝与忽略都不刷新存活轨迹**，因此设备不能靠乱序/重复/伪造上报无限期保持 ONLINE，也不能覆盖更新的状态。

### 1.3 期望状态版本 `DesiredState` 与生命周期 `StatePhase`

每次下发产生一个设备内**单调递增**的 `revision`（1,2,3…），阶段如下：

```
PENDING ──apply 成功/幂等成功──▶ APPLIED   （终态）
PENDING ──新版本下发──────────▶ SUPERSEDED （终态，不再可送达/重试）
PENDING ──owner 撤销──────────▶ REVOKED    （终态，重试不会复活）
PENDING ──未送达/被拒绝/失败──▶ 仍为 PENDING（只能用 retry 显式重试）
```

### 1.4 下发结论 `DeliveryOutcome`（失败原因可区分）

| 结论 | 语义 |
| --- | --- |
| `APPLIED` | 本次送达并在设备侧成功应用 |
| `ALREADY_APPLIED` | 同 revision 设备侧已应用（会话层幂等命中，不会被反复应用） |
| `SUPERSEDED` | 旧 PENDING 版本被更新版本取代 |
| `REVOKED` | 版本被归属方撤销 |
| `NOT_DELIVERED` | **未送达**：设备会话不可达（断链/离线） |
| `REJECTED_INCOMPATIBLE` | **被拒绝**：能力/固件版本不在兼容范围（detail 列明全部违规项） |
| `REJECTED_BY_DEVICE` | **被拒绝**：设备侧业务拒绝 |
| `EXECUTION_FAILED` | **执行失败**：送达后设备执行出错 |
| `NOT_REVOKABLE` | 重试/撤销时不存在 PENDING 版本（含已撤销、已应用等） |

`NOT_DELIVERED / REJECTED_* / EXECUTION_FAILED` 三类失败彼此可区分，且与 HTTP 层
404（目标不存在）、403（越权）、409（重复注册）不混淆。

---

## 2. 收敛规则

1. **版本单调**：revision 由设备账本（`DeviceStateLedger`）在锁内分配，严格 +1；新版本创建时，
   旧的 PENDING 版本立即标记 `SUPERSEDED`。
2. **并发唯一**：每台设备一把锁（`ReentrantLock`），同一设备的 dispatch/retry/revoke 串行化；
   不同设备互不阻塞。N 个并发下发恰好得到 revision 1..N，每个版本只被真正应用一次。
3. **不重复应用**：模拟网关（`GatewaySimulator`）按设备记录已应用 revision 集合；
   对同 revision 的再次 `apply` 返回 `ALREADY_APPLIED`。
4. **已撤销/已取代不复活**：终态版本不会被 `retry` 重新送达；`retry` 只作用于最新 PENDING。
5. **失败保持 PENDING、重试显式化**：未送达/拒绝/失败不改变"最终可达状态"，由归属方显式
   `retry` 或重新 `dispatch`（新版本）推进。
6. **归属隔离**：所有操作先查存在性（不存在 → 404），再校验 `X-Owner-Id` 与设备 owner
   （不匹配 → 403），判定顺序固定，结论稳定可区分。

---

## 3. 兼容范围（`StateRequirements`）

```json
{ "requiredCapabilities": ["gps", "ble"], "minFirmware": "1.2.0", "maxFirmware": "2.0.0" }
```

- `requiredCapabilities`：设备能力集合必须全部包含，缺失逐项列出；
- `minFirmware` / `maxFirmware`：固件点分版本号闭区间比较（缺段补 0，边界版本通过）；
- 任一项不满足：下发结论为 `REJECTED_INCOMPATIBLE`，`detail` 含全部原因（能力缺失 + 版本越界），
  **不静默按旧语义放行**。不兼容版本同样登记入账（停留 PENDING，带拒绝结论）以便审计。

---

## 4. HTTP 入口

| 方法与路径 | 说明 | 归属要求 |
| --- | --- | --- | --- |
| `POST /api/devices` | 注册设备（重复注册 409） | body: deviceId/ownerId/firmwareVersion/capabilities |
| `GET /api/devices/{id}` | 查询设备（查询时按需评估存活；不存在 404） | 无 |
| `POST /api/devices/{id}/reports` | 周期上报 | body: timestamp(ISO-8601)/metrics |
| `POST /api/devices/{id}/desired-state/dispatch` | 下发新版本 | header `X-Owner-Id` |
| `POST /api/devices/{id}/desired-state/retry` | 重试最新 PENDING | header `X-Owner-Id` |
| `POST /api/devices/{id}/desired-state/revoke` | 撤销最新 PENDING | header `X-Owner-Id` |
| `POST /api/ops/liveness/sweep` | 触发一次全量 TTL 扫描 | 无（本地运维） |

---

## 5. 本地验证方法（可重复、无外部依赖）

### 5.1 一键运行全部单测/集成测试

```bash
./mvnw -q test 2>/dev/null || mvn -q test
# 或使用封装脚本
scripts/local-verify.sh
```

覆盖场景（关键输入与判定依据均通过 SLF4J 打印，见测试日志与业务日志）：

- `ReportIngestTest`：正常顺序、重复（`IGNORED_DUPLICATE`）、乱序/回退
  （`IGNORED_STALE_TIMESTAMP` 且不覆盖状态）、缺失/未来时间戳拒绝、TTL 过期转 OFFLINE 与恢复；
- `OrchestrationTest`：重复下发版本单调且不重复应用、**16 线程并发下发唯一收敛**、
  未送达/设备拒绝/执行失败三分类、失败后 retry 恢复、撤销后不复活、不兼容拒绝带原因；
- `CompatibilityPolicyTest`：固件闭区间边界、能力缺失逐条列出；
- `AccessControlTest`：跨 owner 越权（403 语义）与目标不存在（404 语义）可区分；
- `WebApiIntegrationTest`：经 MockMvc 验证全部 HTTP 入口结论与错误码一致。

测试使用 `MutableClock`（可控时钟，见 `src/test/.../tests/MutableClock.java`），
**不需要真实等待时间**，结果完全可重复。

### 5.2 手动联调（启动真实 Web 服务）

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
```

另开终端：

```bash
# 注册
curl -s localhost:8080/api/devices -H 'Content-Type: application/json' -d '{
  "deviceId":"edge-1","ownerId":"team-a","firmwareVersion":"1.4.0",
  "capabilities":["gps","ble"]}'

# 合法上报
curl -s localhost:8080/api/devices/edge-1/reports -H 'Content-Type: application/json' \
  -d '{"timestamp":"2026-09-26T00:00:00Z","metrics":{"cpu":3}}'

# 下发期望状态
curl -s localhost:8080/api/devices/edge-1/desired-state/dispatch \
  -H 'Content-Type: application/json' -H 'X-Owner-Id: team-a' \
  -d '{"payload":{"mode":"eco"},"requirements":{}}'

# 越权（403 OWNER_MISMATCH）
curl -i localhost:8080/api/devices/edge-1/desired-state/retry -X POST -H 'X-Owner-Id: team-b'

# 不存在（404 DEVICE_NOT_FOUND）
curl -i localhost:8080/api/devices/ghost
```

---

## 6. 代码结构

```
model/           设备、上报结论、期望状态/版本阶段、下发结论与请求
registry/        设备注册表（内存、重复注册拒绝）
session/         GatewaySimulator：本地会话+上报通道模拟（可达性/行为/apply 幂等）
report/          ReportIngestService（时间戳判定）、LivenessService（TTL 存活）
compat/          CompatibilityPolicy（能力与固件版本区间）
orchestration/   OrchestrationService + DeviceStateLedger（版本账本与每设备锁）
api/             REST 控制器与全局异常映射（404/403/409/400）
config/          Clock Bean（测试中替换为 MutableClock）
```
