#!/bin/bash
# pitbox-style mutation campaign for the e2e suite (inspired by baekchangjoon/spring-mutation-tests).
#
# Methodology: inject one behavior-changing mutant into the agent SUT at a time, rebuild the agent jar,
# run the out-of-process e2e (HTTP black-box) suite against a FRESH coverage dir, and classify:
#   exit 0  -> SURVIVED  (e2e passed despite the bug = a test gap)
#   exit !=0 -> KILLED    (e2e caught the bug)
# Each mutant is reverted with `git checkout` afterwards.
#
# Usage: JAVA_HOME=<jdk17+> scripts/mutation-e2e.sh
set -u
cd "$(dirname "$0")/.."

TS=src/main/java/io/pjacoco/agent/store/TestStore.java
REG=src/main/java/io/pjacoco/agent/store/TestStoreRegistry.java
SA=src/main/java/io/pjacoco/agent/inbound/servlet/ServletAdvice.java
BP=src/main/java/io/pjacoco/agent/inbound/BaggageParser.java
EW=src/main/java/io/pjacoco/agent/output/ExecWriter.java
BS=src/main/java/io/pjacoco/agent/Bootstrap.java

run () { # id  file
  local id="$1" file="$2"
  if git diff --quiet -- "$file"; then echo "RESULT $id NO_CHANGE(perl-miss) $file"; return; fi
  rm -rf build/coverage
  local log="/tmp/mut_${id}.log"
  if ./gradlew e2eTest --console=plain -q >"$log" 2>&1; then
    echo "RESULT $id SURVIVED $file   <-- e2e GAP"
  elif grep -qiE "compileJava FAILED|error: " "$log"; then
    echo "RESULT $id COMPILE_ERROR(invalid mutant) $file"
  else
    echo "RESULT $id KILLED $file"
  fi
  git checkout -- "$file"
}

echo "=== baseline (no mutation) ==="
rm -rf build/coverage
if ./gradlew e2eTest --console=plain -q >/tmp/mut_baseline.log 2>&1; then echo "RESULT baseline PASS"; else echo "RESULT baseline FAIL (abort)"; exit 1; fi

# M1 isolation: ThreadLocal context -> shared global field (breaks per-thread isolation)
perl -0777 -i -pe 's/\Qprivate static final ThreadLocal<TestStore> ACTIVE = new ThreadLocal<TestStore>();\E/private static volatile TestStore ACTIVE;/; s/\QACTIVE.set(store);\E/ACTIVE = store;/; s/\Qreturn ACTIVE.get();\E/return ACTIVE;/; s/\QACTIVE.remove();\E/ACTIVE = null;/' src/main/java/io/pjacoco/agent/context/CoverageContext.java; run M1_isolation_global src/main/java/io/pjacoco/agent/context/CoverageContext.java

# M2 no coverage recorded
perl -0777 -i -pe 's/\Qp[probeId] = true;\E/p[probeId] = false;/' "$TS";                                  run M2_no_probe_recorded "$TS"
# M3 context not cleared on request exit (thread-reuse leak)
perl -0777 -i -pe 's/\Qtry { CoverageContext.clear(); } catch (Throwable ignored) {}\E/try { if (System.nanoTime()<0) CoverageContext.clear(); } catch (Throwable ignored) {}/' "$SA"; run M3_no_context_clear "$SA"
# M4 strict mode -> lenient auto-create
perl -0777 -i -pe 's/\Q(strict mode, not recorded)");\E\n\s*return null;/(strict mode, not recorded)");\n        return stores.computeIfAbsent(testId, new java.util.function.Function<String, io.pjacoco.agent.store.TestStore>() { public io.pjacoco.agent.store.TestStore apply(String k) { return new io.pjacoco.agent.store.TestStore(k, clock.getAsLong(), null); } });/' "$REG"; run M4_strict_to_lenient "$REG"
# M5 baggage never extracts test.id
perl -0777 -i -pe 's/\Qif (KEY.equals(key)) {\E/if (false) {/' "$BP";                                     run M5_baggage_always_null "$BP"
# M6 sidecar classCount always 0
perl -0777 -i -pe 's/\Q.put("classCount", store.classCount())\E/.put("classCount", 0L)/' "$EW";           run M6_sidecar_classcount_zero "$EW"
# M7 retry does not reset the store
perl -0777 -i -pe 's/\Q        stores.put(testId, store);\E\n\Q        enforceCap();\E/        if (!isRetry) stores.put(testId, store);\n        enforceCap();/' "$REG"; run M7_retry_no_reset "$REG"
# M8 manifest omits commitSha
perl -0777 -i -pe 's/\Q.put("commitSha", commitSha)\E/.put("commitSha", (String) null)/' "$BS";           run M8_manifest_no_commitsha "$BS"
# M9 stop does not flush
perl -0777 -i -pe 's/\Q        flush(s, result, "complete");\E/        if (System.nanoTime()<0) flush(s, result, "complete");/' "$REG"; run M9_stop_no_flush "$REG"

echo "=== done ==="; git status --short
