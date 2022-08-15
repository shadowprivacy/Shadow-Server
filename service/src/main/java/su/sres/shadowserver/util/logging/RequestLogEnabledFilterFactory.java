/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util.logging;

import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.core.filter.Filter;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.dropwizard.logging.filter.FilterFactory;

@JsonTypeName("requestLogEnabled")
class RequestLogEnabledFilterFactory implements FilterFactory<IAccessEvent> {

    @Override
    public Filter<IAccessEvent> build() {
	return RequestLogManager.getHttpRequestLogFilter();
    }
}
