/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.util;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.junit.Rule;
import org.junit.Test;
import su.sres.shadowserver.configuration.MonitoredS3ObjectConfiguration;
import su.sres.shadowserver.redis.AbstractRedisClusterTest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TorExitNodeManagerTest extends AbstractRedisClusterTest {

  @Rule
  public WireMockRule wireMockRule = new WireMockRule(options().dynamicPort().dynamicHttpsPort());
  
  @Test
  public void testIsTorExitNode() {
    final MonitoredS3ObjectConfiguration configuration = new MonitoredS3ObjectConfiguration();
    
    final TorExitNodeManager torExitNodeManager =
        new TorExitNodeManager(mock(ScheduledExecutorService.class), configuration);
    
    when(configuration.getRegion()).thenReturn("ap-northeast-3");
    
    assertFalse(torExitNodeManager.isTorExitNode("10.0.0.1"));
    assertFalse(torExitNodeManager.isTorExitNode("10.0.0.2"));
    
    torExitNodeManager.handleExitListChangedStream(
        new ByteArrayInputStream("10.0.0.1\n10.0.0.2".getBytes(StandardCharsets.UTF_8)));

    assertTrue(torExitNodeManager.isTorExitNode("10.0.0.1"));
    assertTrue(torExitNodeManager.isTorExitNode("10.0.0.2"));
    assertFalse(torExitNodeManager.isTorExitNode("10.0.0.3"));
  }
}
