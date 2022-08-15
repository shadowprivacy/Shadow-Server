/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.metrics;

import com.codahale.metrics.Gauge;
import com.sun.management.UnixOperatingSystemMXBean;

import java.lang.management.ManagementFactory;

/**
 * A gauge that reports the maximum number of file descriptors allowed by the
 * operating system.
 */
public class MaxFileDescriptorGauge implements Gauge<Long> {

    private final UnixOperatingSystemMXBean unixOperatingSystemMXBean;

    public MaxFileDescriptorGauge() {
	this.unixOperatingSystemMXBean = (UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    }

    @Override
    public Long getValue() {
	return unixOperatingSystemMXBean.getMaxFileDescriptorCount();
    }
}
