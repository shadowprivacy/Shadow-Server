/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.limits;

import static com.codahale.metrics.MetricRegistry.name;

import io.dropwizard.util.Duration;
import io.lettuce.core.SetArgs;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicRateLimitsConfiguration;
import su.sres.shadowserver.controllers.RateLimitExceededException;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.Util;

public class UnsealedSenderRateLimiter {

  private final RateLimiters rateLimiters;
  private final FaultTolerantRedisCluster rateLimitCluster;
  private final DynamicConfiguration dynamicConfig;
  private final RateLimitResetMetricsManager metricsManager;

  private static final String RATE_LIMIT_RESET_COUNTER_NAME = name(UnsealedSenderRateLimiter.class, "reset");
  private static final String RATE_LIMITED_UNSEALED_SENDER_COUNTER_NAME = name(UnsealedSenderRateLimiter.class, "rateLimited");
  private static final String RATE_LIMITED_UNSEALED_SENDER_ACCOUNTS_TOTAL_COUNTER_NAME = name(UnsealedSenderRateLimiter.class, "rateLimitedAccountsTotal");
  private static final String RATE_LIMITED_UNSEALED_SENDER_ACCOUNTS_ENFORCED_COUNTER_NAME = name(UnsealedSenderRateLimiter.class, "rateLimitedAccountsEnforced");
  private static final String RATE_LIMITED_UNSEALED_SENDER_ACCOUNTS_UNENFORCED_COUNTER_NAME = name(UnsealedSenderRateLimiter.class, "rateLimitedAccountsUnenforced");

  private static final String RATE_LIMITED_ACCOUNTS_HLL_KEY = "UnsealedSenderRateLimiter::rateLimitedAccounts::total";
  private static final String RATE_LIMITED_ACCOUNTS_ENFORCED_HLL_KEY = "UnsealedSenderRateLimiter::rateLimitedAccounts::enforced";
  private static final String RATE_LIMITED_ACCOUNTS_UNENFORCED_HLL_KEY = "UnsealedSenderRateLimiter::rateLimitedAccounts::unenforced";
  private static final long RATE_LIMITED_ACCOUNTS_HLL_TTL_SECONDS = Duration.days(1).toSeconds();


  public UnsealedSenderRateLimiter(final RateLimiters rateLimiters,
      final FaultTolerantRedisCluster rateLimitCluster,
      final DynamicConfiguration dynamicConfig,
      final RateLimitResetMetricsManager metricsManager) {

    this.rateLimiters = rateLimiters;
    this.rateLimitCluster = rateLimitCluster;
    this.dynamicConfig = dynamicConfig;
    this.metricsManager = metricsManager;

    metricsManager.initializeFunctionCounters(RATE_LIMITED_UNSEALED_SENDER_ACCOUNTS_TOTAL_COUNTER_NAME,
        RATE_LIMITED_ACCOUNTS_HLL_KEY);
    metricsManager.initializeFunctionCounters(RATE_LIMITED_UNSEALED_SENDER_ACCOUNTS_ENFORCED_COUNTER_NAME,
        RATE_LIMITED_ACCOUNTS_ENFORCED_HLL_KEY);
    metricsManager.initializeFunctionCounters(RATE_LIMITED_UNSEALED_SENDER_ACCOUNTS_UNENFORCED_COUNTER_NAME,
        RATE_LIMITED_ACCOUNTS_UNENFORCED_HLL_KEY);
  }

  public void validate(final Account sender, final Account destination) throws RateLimitExceededException {
    final int maxCardinality = rateLimitCluster.withCluster(connection -> {
      final String cardinalityString = connection.sync().get(getMaxCardinalityKey(sender));

      return cardinalityString != null
          ? Integer.parseInt(cardinalityString)
          : dynamicConfig.getLimits().getUnsealedSenderDefaultCardinalityLimit();
    });

    try {
      rateLimiters.getUnsealedSenderCardinalityLimiter()
          .validate(sender.getUuid().toString(), destination.getUuid().toString(), maxCardinality);
    } catch (final RateLimitExceededException e) {

      final boolean enforceLimit = dynamicConfig.getRateLimitChallengeConfiguration().isUnsealedSenderLimitEnforced();

      metricsManager.recordMetrics(sender, enforceLimit, RATE_LIMITED_UNSEALED_SENDER_COUNTER_NAME,
          enforceLimit ? RATE_LIMITED_ACCOUNTS_ENFORCED_HLL_KEY : RATE_LIMITED_ACCOUNTS_UNENFORCED_HLL_KEY,
          RATE_LIMITED_ACCOUNTS_HLL_KEY,
          RATE_LIMITED_ACCOUNTS_HLL_TTL_SECONDS
          );

      if (enforceLimit) {
        throw e;
      }
    }
  }

  public void handleRateLimitReset(final Account account) {
    rateLimitCluster.useCluster(connection -> {
      final CardinalityRateLimiter unsealedSenderCardinalityLimiter = rateLimiters.getUnsealedSenderCardinalityLimiter();
      final DynamicRateLimitsConfiguration rateLimitsConfiguration =
          dynamicConfig.getLimits();

      final long ttl;
      {
        final long remainingTtl = unsealedSenderCardinalityLimiter.getRemainingTtl(account.getUuid().toString());
        ttl = remainingTtl > 0 ? remainingTtl : unsealedSenderCardinalityLimiter.getInitialTtl().toSeconds();
      }

      final String key = getMaxCardinalityKey(account);

      connection.sync().set(key,
          String.valueOf(rateLimitsConfiguration.getUnsealedSenderDefaultCardinalityLimit()),
          SetArgs.Builder.nx().ex(ttl));

      connection.sync().incrby(key, rateLimitsConfiguration.getUnsealedSenderPermitIncrement());
    });

    Metrics.counter(RATE_LIMIT_RESET_COUNTER_NAME,
        "countryCode", Util.getCountryCode(account.getUserLogin())).increment();
  }

  private static String getMaxCardinalityKey(final Account account) {
    return "max_unsealed_sender_cardinality::" + account.getUuid();
  }
}
