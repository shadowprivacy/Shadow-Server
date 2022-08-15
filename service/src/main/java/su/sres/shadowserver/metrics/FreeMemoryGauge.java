/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.metrics;

import com.codahale.metrics.Gauge;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;

public class FreeMemoryGauge implements Gauge<Long> {

    private final OperatingSystemMXBean operatingSystemMXBean;

    public FreeMemoryGauge() {
	this.operatingSystemMXBean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public Long getValue() {
	return operatingSystemMXBean.getFreePhysicalMemorySize();
    }
}