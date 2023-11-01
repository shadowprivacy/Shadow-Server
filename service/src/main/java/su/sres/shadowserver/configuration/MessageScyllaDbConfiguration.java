/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.configuration;

import javax.validation.Valid;
import java.time.Duration;

public class MessageScyllaDbConfiguration extends ScyllaDbConfiguration {

    private Duration timeToLive = Duration.ofDays(14);

    @Valid
    public Duration getTimeToLive() {
	return timeToLive;
    }
}
