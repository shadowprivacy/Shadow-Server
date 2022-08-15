/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.metrics;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;

public class BufferPoolGauges {

    private BufferPoolGauges() {
    }

    public static void registerMetrics() {
	for (final BufferPoolMXBean bufferPoolMXBean : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
	    final List<Tag> tags = List.of(Tag.of("name", bufferPoolMXBean.getName()));

	    Metrics.gauge(name(BufferPoolGauges.class, "count"), tags, bufferPoolMXBean, BufferPoolMXBean::getCount);
	    Metrics.gauge(name(BufferPoolGauges.class, "memory_used"), tags, bufferPoolMXBean, BufferPoolMXBean::getMemoryUsed);
	    Metrics.gauge(name(BufferPoolGauges.class, "total_capacity"), tags, bufferPoolMXBean, BufferPoolMXBean::getTotalCapacity);
	}
    }
}
