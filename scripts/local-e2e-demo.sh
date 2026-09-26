#!/usr/bin/env bash
# 本地端到端演示：需要应用已启动（mvn spring-boot:run），默认端口 8080，可通过参数覆盖。
# 仅使用 curl，不依赖任何外部服务。
set -u
PORT="${1:-8080}"
BASE="http://localhost:${PORT}/api/fleet"

echo "== 1. 注册设备 gw-demo（owner=acme, firmware=2.1.0, canbus） =="
curl -s -X POST "$BASE/devices" -H 'Content-Type: application/json' -d '{
  "deviceId":"gw-demo","owner":"acme","firmware":"2.1.0",
  "capabilities":["canbus"],
  "acceptedRange":{"minFirmware":"2.0","maxFirmware":"3.0","requiredCapability":"canbus"}}'; echo

RANGE='"acceptedRange":{"minFirmware":"2.0","maxFirmware":"3.0","requiredCapability":"canbus"}'
# 取当前 UTC 时刻与前一秒，保证上报通过新鲜度窗口（真实时钟下可重复）
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
PREV="$(date -u -d '1 second ago' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v-1S +%Y-%m-%dT%H:%M:%SZ)"

echo "== 2. 正常上报 seq=1 -> ACCEPTED/ONLINE；乱序 seq=0 -> REJECTED_TIMESTAMP_REGRESSED =="
curl -s -X POST "$BASE/devices/gw-demo/reports" -H 'Content-Type: application/json' \
  -d "{\"seq\":1,\"reportTime\":\"$NOW\"}"; echo
curl -s -X POST "$BASE/devices/gw-demo/reports" -H 'Content-Type: application/json' \
  -d "{\"seq\":0,\"reportTime\":\"$PREV\"}"; echo

echo "== 3. 未注册设备上报 -> REJECTED_UNREGISTERED =="
curl -s -X POST "$BASE/devices/ghost/reports" -H 'Content-Type: application/json' \
  -d "{\"seq\":1,\"reportTime\":\"$NOW\"}"; echo

echo "== 4. 下发期望状态 revision=1 -> APPLIED；再次 reconcile -> ALREADY_APPLIED =="
curl -s -X POST "$BASE/devices/gw-demo/desired" -H 'Content-Type: application/json' \
  -d "{\"owner\":\"acme\",\"config\":{\"mode\":\"fast\"},$RANGE}"; echo
curl -s -X POST "$BASE/devices/gw-demo/revisions/1/reconcile" -H 'Content-Type: application/json' \
  -d "{\"owner\":\"acme\",$RANGE}"; echo

echo "== 5. 越权（owner=globex）-> 403 FORBIDDEN_OWNER；不存在 -> 404 NOT_FOUND =="
curl -s -i -X POST "$BASE/devices/gw-demo/desired" -H 'Content-Type: application/json' \
  -d "{\"owner\":\"globex\",\"config\":{\"mode\":\"x\"},$RANGE}" | head -1
curl -s -i -X GET "$BASE/devices/ghost?owner=acme" | head -1

echo "== 6. 不兼容（要求 2.5+ 与 zigbee）-> 422 INCOMPATIBLE =="
curl -s -i -X POST "$BASE/devices/gw-demo/desired" -H 'Content-Type: application/json' \
  -d '{"owner":"acme","config":{"mode":"x"},
       "acceptedRange":{"minFirmware":"2.5","maxFirmware":"3.0","requiredCapability":"zigbee"}}' | head -1

echo "== 7. 修订历史（越权下发未产生修订，仅保留 revision 1） =="
curl -s "$BASE/devices/gw-demo/revisions?owner=acme"; echo
