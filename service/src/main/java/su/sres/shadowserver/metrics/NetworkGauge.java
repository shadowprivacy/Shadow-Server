/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.metrics;


import com.codahale.metrics.Gauge;

import su.sres.shadowserver.util.Pair;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public abstract class NetworkGauge implements Gauge<Double> {

  protected Pair<Long, Long> getSentReceived() throws IOException {
    File proc = new File("/proc/net/dev");
    try (BufferedReader reader = new BufferedReader(new FileReader(proc))) {
      reader.readLine(); // header
      reader.readLine(); // header2

    long           bytesSent     = 0;
    long           bytesReceived = 0;

    String interfaceStats;

      while ((interfaceStats = reader.readLine()) != null) {
        String[] stats = interfaceStats.split("\\s+");

        if (!stats[1].equals("lo:")) {
          bytesReceived += Long.parseLong(stats[2]);
          bytesSent     += Long.parseLong(stats[10]);
        }
      }

      return new Pair<>(bytesSent, bytesReceived);
    }
  }
}
