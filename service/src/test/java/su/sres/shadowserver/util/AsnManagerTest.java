/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.util;

import org.junit.jupiter.api.Test;

import io.minio.MinioClient;
import su.sres.shadowserver.configuration.MinioConfiguration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AsnManagerTest {

  @Test
  void getAsn() throws IOException {
    final MinioConfiguration configuration = mock(MinioConfiguration.class);
    configuration.setRegion("ap-northeast-3");
    
    when(configuration.getUri()).thenReturn("https://example.com");
    when(configuration.getAccessKey()).thenReturn("12345");
    when(configuration.getAccessSecret()).thenReturn("67890");

    final AsnManager asnManager = new AsnManager(mock(ScheduledExecutorService.class), configuration);
   
    assertEquals(Optional.empty(), asnManager.getAsn("10.0.0.1"));

    try (final InputStream tableInputStream = getClass().getResourceAsStream("ip2asn-test.tsv")) {
      asnManager.handleAsnTableChangedStream(tableInputStream);
    }

    assertEquals(Optional.of(7922L), asnManager.getAsn("50.79.54.1"));
    assertEquals(Optional.of(7552L), asnManager.getAsn("27.79.32.1"));
    assertEquals(Optional.empty(), asnManager.getAsn("32.79.117.1"));
    assertEquals(Optional.empty(), asnManager.getAsn("10.0.0.1"));
  }
}
