/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.push;

import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.storage.PubSubManager;
import su.sres.shadowserver.storage.PubSubProtos;
import su.sres.shadowserver.websocket.ProvisioningAddress;

import static com.codahale.metrics.MetricRegistry.name;

public class ProvisioningManager {
    private final PubSubManager pubSubManager;

    private final Counter provisioningMessageOnlineCounter = Metrics.counter(name(getClass(), "sendProvisioningMessage"), "online", "true");
    private final Counter provisioningMessageOfflineCounter = Metrics.counter(name(getClass(), "sendProvisioningMessage"), "online", "false");

    public ProvisioningManager(final PubSubManager pubSubManager) {
	this.pubSubManager = pubSubManager;
    }

    public boolean sendProvisioningMessage(ProvisioningAddress address, byte[] body) {
	PubSubProtos.PubSubMessage pubSubMessage = PubSubProtos.PubSubMessage.newBuilder()
		.setType(PubSubProtos.PubSubMessage.Type.DELIVER)
		.setContent(ByteString.copyFrom(body))
		.build();

	if (pubSubManager.publish(address, pubSubMessage)) {
	    provisioningMessageOnlineCounter.increment();
	    return true;
	} else {
	    provisioningMessageOfflineCounter.increment();
	    return false;
	}
    }
}
