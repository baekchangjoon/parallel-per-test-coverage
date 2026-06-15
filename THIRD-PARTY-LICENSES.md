# Third-party licenses

The pjacoco project's own source code is licensed under the **MIT License** (see [`LICENSE`](LICENSE)).

The **distributed agent jar** (`io.pjacoco:pjacoco-agent`, i.e. the `-javaagent:jacocoagent-parallel.jar`)
additionally **embeds** the following third-party components, relocated into the `io.pjacoco.shaded.*`
namespace. Their original license and notice files are preserved inside the jar (`about.html`,
`META-INF/NOTICE`, `META-INF/licenses/`, and the original Maven metadata under
`META-INF/maven/org.jacoco/...`). **These components remain under their own licenses and are NOT
relicensed under MIT.**

| Component | Coordinate | Version | License | Source |
|---|---|---|---|---|
| JaCoCo core | `org.jacoco:org.jacoco.core` | 0.8.12 | **Eclipse Public License 2.0 (EPL-2.0)** | https://github.com/jacoco/jacoco |
| Byte Buddy | `net.bytebuddy:byte-buddy` | 1.14.18 | Apache License 2.0 | https://github.com/raphw/byte-buddy |
| ASM | bundled within the above | — | BSD-3-Clause | https://asm.ow2.io |

- EPL-2.0 full text: https://www.eclipse.org/legal/epl-2.0/
- Apache-2.0 full text: https://www.apache.org/licenses/LICENSE-2.0
- The complete and corresponding source for the embedded JaCoCo version is available from Maven Central
  (`org.jacoco:org.jacoco.core:0.8.12`) and the JaCoCo repository linked above.

The remaining dependencies (JUnit, Jetty, Mockito, REST Assured, the servlet APIs, the JUnit
platform/vintage engines, `byte-buddy-agent`, etc.) are used **only at build/test time** and are **not
redistributed** in any published artifact.

## Acknowledgement (no code reused)

The agent's probe-routing technique was implemented **independently** after studying the publicly
documented hooking mechanism in Datadog's [`dd-trace-java`](https://github.com/DataDog/dd-trace-java)
(Apache License 2.0). **No Datadog source code is copied or redistributed** — only the technique/pattern
(hooking the probe-insertion site at instrumentation time and emitting an additive coverage call) was
borrowed, and the `recordCoverage(Class, long, int)` bridge signature mirrors the same call shape.
