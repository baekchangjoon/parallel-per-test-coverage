package io.pjacoco.testkit;

/**
 * Wire format choices for propagating the active test ID to a server under test.
 *
 * <p><strong>Wire formats:</strong>
 *
 * <ul>
 *   <li><strong>W3C_BAGGAGE:</strong> OpenTelemetry W3C Baggage standard header. Sends the test ID
 *       as {@code baggage: test.id=<id>}. Use this when the SUT's tracing stack (e.g. OpenTelemetry)
 *       consumes the standard Baggage header. This is the default and most interoperable choice.
 *   <li><strong>FIELD:</strong> Brave field-header convention. Sends the test ID as a dedicated HTTP
 *       header {@code test.id: <id>}. Use this when the SUT uses Brave/Micrometer
 *       ({@code management.tracing.baggage.remote-fields=test.id}) to pick up the baggage key from a
 *       request header, without relying on the W3C Baggage header. This is useful for SUTs that need
 *       first-class header support or do not use OpenTelemetry.
 *   <li><strong>BOTH:</strong> Send both W3C_BAGGAGE and FIELD formats in the same request. Use this
 *       when the SUT may consume either format (for compatibility across different middleware versions
 *       or deployment configurations).
 * </ul>
 *
 * <p>The test ID is always propagated <em>raw and unencoded</em> (so that characters like {@code #}
 * remain legible in traces and logs). Only query parameters in the control-URL are URL-encoded.
 *
 * @see Pjacoco#fieldHeaderName()
 * @see Pjacoco#fieldHeaderValue()
 */
public enum HeaderStyle {
    /** OpenTelemetry W3C Baggage standard: {@code baggage: test.id=<id>}. */
    W3C_BAGGAGE,
    /** Brave field-header convention: {@code test.id: <id>}. */
    FIELD,
    /** Both W3C Baggage and field-header formats in the same request. */
    BOTH
}
