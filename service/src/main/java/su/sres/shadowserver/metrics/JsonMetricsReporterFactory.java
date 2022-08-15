/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.metrics;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.ScheduledReporter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import javax.validation.constraints.NotNull;
import java.net.UnknownHostException;

import io.dropwizard.metrics.BaseReporterFactory;

@JsonTypeName("json")
public class JsonMetricsReporterFactory extends BaseReporterFactory {

  @JsonProperty
  @NotNull
  private String hostname;

  @JsonProperty
  @NotNull
  private String token;

  @Override
  public ScheduledReporter build(MetricRegistry metricRegistry) {
    try {
      return JsonMetricsReporter.forRegistry(metricRegistry)
                                .withHostname(hostname)
                                .withToken(token)
                                .convertRatesTo(getRateUnit())
                                .convertDurationsTo(getDurationUnit())
                                .filter(getFilter())
                                .build();
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }
}
