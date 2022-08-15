/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util.logging;

import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.core.filter.Filter;

public class RequestLogManager {
    private static final RequestLogEnabledFilter<IAccessEvent> HTTP_REQUEST_LOG_FILTER = new RequestLogEnabledFilter<>();

    static Filter<IAccessEvent> getHttpRequestLogFilter() {
        return HTTP_REQUEST_LOG_FILTER;
    }

    public static void setRequestLoggingEnabled(final boolean enabled) {
        HTTP_REQUEST_LOG_FILTER.setRequestLoggingEnabled(enabled);
    }
}
