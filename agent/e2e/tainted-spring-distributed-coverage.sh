#!/usr/bin/env bash
# REQ-015 live E2E (OTel vector): cross-service per-test coverage merge on the tainted-spring stack.
#
# Flow: POST /internal/diaries → diary (producer, JVM-1) → Kafka topic diary.created
#       → mindgraph (consumer JVM-2, DiaryCreatedConsumer) — OTel javaagent propagates the W3C
#       traceparent across the Kafka hop; pjacoco attributes each service's coverage per OTel traceId.
# Propagation: OpenTelemetry (W3C traceparent), OTel javaagent 2.11.0.
#
# Usage:
#   TAINTED_SPRING_ROOT=/path/to/tainted-spring-platform \
#   bash agent/e2e/tainted-spring-distributed-coverage.sh
#
# Requirements (host):
#   - Docker running and reachable (`docker info` exit 0)
#   - TAINTED_SPRING_ROOT dir present with docker-compose.yml + jacoco/opentelemetry-javaagent.jar
#   - Built agent jar (runs ./gradlew :agent:shadowJar if absent); curl, java on PATH

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TAINTED_SPRING_ROOT="${TAINTED_SPRING_ROOT:-${HOME}/github_tainted-spring/tainted-spring-platform}"
AGENT_JAR="${REPO_ROOT}/agent/build/libs/pjacoco-agent.jar"
OTEL_JAR="${TAINTED_SPRING_ROOT}/jacoco/opentelemetry-javaagent.jar"

FIXED_TRACE_ID="cccccccccccccccccccccccccccccccc"   # 32-hex W3C traceId
FIXED_SPAN_ID="cccccccccccccccc"                     # 16-hex span
DIARY_URL="http://localhost:8082/internal/diaries"
TEST_ID="com.tainted.DiaryFlowE2E#publishesAcrossKafka"

COV_ROOT=""; OVERLAY_FILE=""; MAP_FILE=""; OUT_DIR=""

cleanup() {
    local ec=$?
    echo "[e2e] cleanup: tearing down Docker stack..."
    if [[ -n "${OVERLAY_FILE:-}" && -f "${OVERLAY_FILE}" ]]; then
        docker compose -f "${TAINTED_SPRING_ROOT}/docker-compose.yml" -f "${OVERLAY_FILE}" down -v 2>/dev/null || true
        rm -f "${OVERLAY_FILE}"
    fi
    [[ -n "${COV_ROOT:-}" && -d "${COV_ROOT}" ]] && rm -rf "${COV_ROOT}"
    [[ -n "${MAP_FILE:-}"  && -f "${MAP_FILE}"  ]] && rm -f  "${MAP_FILE}"
    [[ -n "${OUT_DIR:-}"   && -d "${OUT_DIR}"   ]] && rm -rf "${OUT_DIR}"
    exit "${ec}"
}
trap cleanup EXIT

# --- Pre-flight (skip cleanly when env absent) ---
if ! docker info >/dev/null 2>&1; then
    echo "[e2e] SKIP: Docker not reachable — skipping REQ-015 OTel live E2E."; exit 0; fi
if [[ ! -d "${TAINTED_SPRING_ROOT}" ]]; then
    echo "[e2e] SKIP: TAINTED_SPRING_ROOT not found: ${TAINTED_SPRING_ROOT} — skipping."; exit 0; fi
if [[ ! -f "${TAINTED_SPRING_ROOT}/docker-compose.yml" ]]; then
    echo "[e2e] SKIP: ${TAINTED_SPRING_ROOT}/docker-compose.yml not found — skipping."; exit 0; fi
if [[ ! -f "${OTEL_JAR}" ]]; then
    echo "[e2e] SKIP: OTel javaagent not found: ${OTEL_JAR} — skipping."; exit 0; fi

# --- Step 1: build agent jar if absent ---
if [[ ! -f "${AGENT_JAR}" ]]; then
    echo "[e2e] Building agent shadowJar..."; (cd "${REPO_ROOT}" && ./gradlew :agent:shadowJar -q); fi
echo "[e2e] Agent jar: ${AGENT_JAR}"

# --- Step 2: coverage dirs ---
COV_ROOT="$(mktemp -d)/coverage"
mkdir -p "${COV_ROOT}/diary" "${COV_ROOT}/mindgraph"

# --- Step 3: generate overlay (OTel agent FIRST, then pjacoco — GA-3 order) ---
OVERLAY_FILE="$(mktemp -d)/docker-compose.pjacoco-otel-distributed.yml"
cat > "${OVERLAY_FILE}" <<OVERLAY
name: tainted-spring-platform
services:
  diary:
    volumes:
      - ${OTEL_JAR}:/opt/otel/otel.jar:ro
      - ${AGENT_JAR}:/opt/pjacoco/agent.jar:ro
      - ${COV_ROOT}/diary:/coverage
    environment:
      JAVA_TOOL_OPTIONS: >-
        -javaagent:/opt/otel/otel.jar
        -javaagent:/opt/pjacoco/agent.jar=destfile=/coverage,includes=com.tainted.diary.*,traceKeyAutoCreate=true,traceIdleFlushMillis=5000,traceReaperIntervalMillis=2000,traceLateWriteGraceMillis=2000,maxstores=5000
      OTEL_SERVICE_NAME: diary
      OTEL_TRACES_EXPORTER: none
      OTEL_METRICS_EXPORTER: none
      OTEL_LOGS_EXPORTER: none
      OTEL_PROPAGATORS: tracecontext,baggage
  mindgraph:
    volumes:
      - ${OTEL_JAR}:/opt/otel/otel.jar:ro
      - ${AGENT_JAR}:/opt/pjacoco/agent.jar:ro
      - ${COV_ROOT}/mindgraph:/coverage
    environment:
      JAVA_TOOL_OPTIONS: >-
        -javaagent:/opt/otel/otel.jar
        -javaagent:/opt/pjacoco/agent.jar=destfile=/coverage,includes=com.tainted.mindgraph.*,traceKeyAutoCreate=true,traceIdleFlushMillis=5000,traceReaperIntervalMillis=2000,traceLateWriteGraceMillis=2000,maxstores=5000
      OTEL_SERVICE_NAME: mindgraph
      OTEL_TRACES_EXPORTER: none
      OTEL_METRICS_EXPORTER: none
      OTEL_LOGS_EXPORTER: none
      OTEL_PROPAGATORS: tracecontext,baggage
      OTEL_INSTRUMENTATION_KAFKA_ENABLED: "true"
OVERLAY
echo "[e2e] Overlay: ${OVERLAY_FILE}"

# --- Step 4: bring up diary→mindgraph subset + infra ---
echo "[e2e] Starting tainted-spring (diary + mindgraph + kafka + postgres)..."
docker compose -f "${TAINTED_SPRING_ROOT}/docker-compose.yml" -f "${OVERLAY_FILE}" \
    up -d --build --wait zookeeper kafka postgres diary mindgraph
echo "[e2e] Stack up."

# --- Step 5: fire requests with a fixed W3C traceparent ---
echo "[e2e] POST /internal/diaries with traceparent traceId=${FIXED_TRACE_ID}..."
for i in 1 2 3; do
    code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${DIARY_URL}" \
        -H 'Content-Type: application/json' \
        -H "traceparent: 00-${FIXED_TRACE_ID}-${FIXED_SPAN_ID}-01" \
        -d '{"userId":"e2e-user","title":"hello","content":"c","primaryEmotion":"joy","energyScore":5}')
    echo "[e2e]   POST #${i} → HTTP ${code}"
    [[ "${code}" =~ ^20[0-9]$ ]] || { echo "[e2e] ERROR: unexpected HTTP ${code}"; exit 1; }
done

# --- Step 6: wait for reaper flush + Kafka consumer drain ---
echo "[e2e] Waiting 25s for idle-reaper flush + Kafka consumer drain..."
sleep 25

# --- Step 7: central trace-map ---
MAP_FILE="$(mktemp -d)/trace-map.properties"
echo "${FIXED_TRACE_ID}=${TEST_ID}" > "${MAP_FILE}"

# --- Step 8: merge ---
OUT_DIR="$(mktemp -d)/report"
echo "[e2e] Running TraceMergeMain..."
java -cp "${AGENT_JAR}" io.pjacoco.agent.output.TraceMergeMain \
    --shared "${COV_ROOT}" --map "${MAP_FILE}" --report "${OUT_DIR}" --drain-wait-ms 0

# --- Step 9: assert downstream (mindgraph Kafka consumer) coverage under the testId ---
MIN_BYTES=70   # a session-only .exec is ~69B; require ExecutionData present
PASS=1
escaped="${TEST_ID}"
for svc in diary mindgraph; do
    f="${OUT_DIR}/${svc}/${escaped}.exec"
    if [[ -f "${f}" ]]; then
        sz=$(wc -c < "${f}"); echo "[e2e] ${svc}/${escaped}.exec — ${sz} bytes"
        [[ "${sz}" -ge "${MIN_BYTES}" ]] || { echo "[e2e] FAIL: ${svc} report too small (${sz}B)"; PASS=0; }
    else
        echo "[e2e] FAIL: missing ${f}"; PASS=0
    fi
done

if [[ "${PASS}" -eq 1 ]]; then
    echo ""
    echo "REQ-015 PASS (OTel) — cross-service per-test coverage merged (OTel/tainted-spring)"
    echo "  traceId : ${FIXED_TRACE_ID}"
    echo "  testId  : ${TEST_ID}"
    echo "  diary + mindgraph(Kafka consumer downstream) coverage attributed to one testId"
    exit 0
else
    echo "REQ-015 FAIL (OTel) — see above"
    exit 1
fi
