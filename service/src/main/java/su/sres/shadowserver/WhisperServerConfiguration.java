/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver;

import com.fasterxml.jackson.annotation.JsonProperty;

import su.sres.websocket.configuration.WebSocketConfiguration;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.dropwizard.Configuration;
import io.dropwizard.client.JerseyClientConfiguration;

import su.sres.shadowserver.configuration.*;

/** @noinspection MismatchedQueryAndUpdateOfCollection, WeakerAccess */
public class WhisperServerConfiguration extends Configuration {

    @NotNull
    @Valid
    @JsonProperty
    private PushConfiguration push;

    @NotNull
    @Valid
    @JsonProperty
    private AwsAttachmentsConfiguration awsAttachments;

    @NotNull
    @Valid
    @JsonProperty
    private AwsAttachmentsConfiguration debuglogs;

    @NotNull
    @Valid
    @JsonProperty
    private CdnConfiguration cdn;

    @NotNull
    @Valid
    @JsonProperty
    private MicrometerConfiguration micrometer;

    @NotNull
    @Valid
    @JsonProperty
    private RedisClusterConfiguration cacheCluster;

    @NotNull
    @Valid
    @JsonProperty
    private RedisConfiguration pubsub;

    @NotNull
    @Valid
    @JsonProperty
    private RedisClusterConfiguration metricsCluster;

    @NotNull
    @Valid
    @JsonProperty
    private RedisConfiguration directory;

    @NotNull
    @Valid
    @JsonProperty
    private AccountDatabaseCrawlerConfiguration accountDatabaseCrawler;

    @NotNull
    @Valid
    @JsonProperty
    private RedisConfiguration pushScheduler;

    @NotNull
    @Valid
    @JsonProperty
    private RedisClusterConfiguration pushSchedulerCluster;

    @NotNull
    @Valid
    @JsonProperty
    private MessageCacheConfiguration messageCache;

    @NotNull
    @Valid
    @JsonProperty
    private RedisClusterConfiguration clientPresenceCluster;

    @Valid
    @NotNull
    @JsonProperty
    private DatabaseConfiguration messageStore;

    @Valid
    @NotNull
    @JsonProperty
    private DatabaseConfiguration abuseDatabase;

    @Valid
    @NotNull
    @JsonProperty
    private List<TestDeviceConfiguration> testDevices = new LinkedList<>();

    @Valid
    @NotNull
    @JsonProperty
    private List<MaxDeviceConfiguration> maxDevices = new LinkedList<>();

    /*
     * excluded federation configuration, let's preserve for future purposes
     * 
     * @Valid
     * 
     * @JsonProperty private FederationConfiguration federation = new
     * FederationConfiguration();
     * 
     */

    @Valid
    @NotNull
    @JsonProperty
    private AccountsDatabaseConfiguration accountsDatabase;

    @Valid
    @NotNull
    @JsonProperty
    private RateLimitsConfiguration limits = new RateLimitsConfiguration();

    @Valid
    @NotNull
    @JsonProperty
    private JerseyClientConfiguration httpClient = new JerseyClientConfiguration();

    @Valid
    @NotNull
    @JsonProperty
    private WebSocketConfiguration webSocket = new WebSocketConfiguration();

    @Valid
    @NotNull
    @JsonProperty
    private TurnConfiguration turn;

    @Valid
    @NotNull
    @JsonProperty
    private GcmConfiguration gcm;

    @Valid
    @NotNull
    @JsonProperty
    private ApnConfiguration apn;

    @Valid
    @NotNull
    @JsonProperty
    private UnidentifiedDeliveryConfiguration unidentifiedDelivery;

    @Valid
    @NotNull
    @JsonProperty
    private RecaptchaConfiguration recaptcha;

    @Valid
    @NotNull
    @JsonProperty
    private SecureStorageServiceConfiguration storageService;

    @NotNull
    @JsonProperty
    private LocalParametersConfiguration localParametersConfiguration;

    @NotNull
    @JsonProperty
    private ServiceConfiguration serviceConfiguration;

    @Valid
    @NotNull
    @JsonProperty
    private PaymentsServiceConfiguration paymentsService;

    @Valid
    @NotNull
    @JsonProperty
    private ZkConfig zkConfig;

    @Valid
    @NotNull
    @JsonProperty
    private RemoteConfigConfiguration remoteConfig;

    private Map<String, String> transparentDataIndex = new HashMap<>();

    public RecaptchaConfiguration getRecaptchaConfiguration() {
	return recaptcha;
    }

    public WebSocketConfiguration getWebSocketConfiguration() {
	return webSocket;
    }

    public PushConfiguration getPushConfiguration() {
	return push;
    }

    public JerseyClientConfiguration getJerseyClientConfiguration() {
	return httpClient;
    }

    public AwsAttachmentsConfiguration getAwsAttachmentsConfiguration() {
	return awsAttachments;
    }

    public AwsAttachmentsConfiguration getDebugLogsConfiguration() {
	return debuglogs;
    }

    public RedisClusterConfiguration getCacheClusterConfiguration() {
	return cacheCluster;
    }

    public RedisConfiguration getPubsubCacheConfiguration() {
	return pubsub;
    }

    public RedisClusterConfiguration getMetricsClusterConfiguration() {
	return metricsCluster;
    }

    public RedisConfiguration getDirectoryConfiguration() {
	return directory;
    }

    public SecureStorageServiceConfiguration getSecureStorageServiceConfiguration() {
	return storageService;
    }

    public AccountDatabaseCrawlerConfiguration getAccountDatabaseCrawlerConfiguration() {
	return accountDatabaseCrawler;
    }

    public MessageCacheConfiguration getMessageCacheConfiguration() {
	return messageCache;
    }

    public RedisClusterConfiguration getClientPresenceClusterConfiguration() {
	return clientPresenceCluster;
    }

    public RedisConfiguration getPushScheduler() {
	return pushScheduler;
    }

    public RedisClusterConfiguration getPushSchedulerCluster() {
	return pushSchedulerCluster;
    }

    public DatabaseConfiguration getMessageStoreConfiguration() {
	return messageStore;
    }

    public DatabaseConfiguration getAbuseDatabaseConfiguration() {
	return abuseDatabase;
    }

    public AccountsDatabaseConfiguration getAccountsDatabaseConfiguration() {
	return accountsDatabase;
    }

    public RateLimitsConfiguration getLimitsConfiguration() {
	return limits;
    }

    /*
     * excluded federation configuration, let's preserve for future purposes
     * 
     * public FederationConfiguration getFederationConfiguration() { return
     * federation; }
     * 
     */

    public TurnConfiguration getTurnConfiguration() {
	return turn;
    }

    public GcmConfiguration getGcmConfiguration() {
	return gcm;
    }

    public ApnConfiguration getApnConfiguration() {
	return apn;
    }

    public CdnConfiguration getCdnConfiguration() {
	return cdn;
    }

    public MicrometerConfiguration getMicrometerConfiguration() {
	return micrometer;
    }

    public UnidentifiedDeliveryConfiguration getDeliveryCertificate() {
	return unidentifiedDelivery;
    }

    public Map<String, Integer> getTestDevices() {
	Map<String, Integer> results = new HashMap<>();

	for (TestDeviceConfiguration testDeviceConfiguration : testDevices) {
	    results.put(testDeviceConfiguration.getNumber(), testDeviceConfiguration.getCode());
	}

	return results;
    }

    public Map<String, Integer> getMaxDevices() {
	Map<String, Integer> results = new HashMap<>();

	for (MaxDeviceConfiguration maxDeviceConfiguration : maxDevices) {
	    results.put(maxDeviceConfiguration.getNumber(), maxDeviceConfiguration.getCount());
	}

	return results;
    }

    public Map<String, String> getTransparentDataIndex() {
	return transparentDataIndex;
    }

    public LocalParametersConfiguration getLocalParametersConfiguration() {
	return localParametersConfiguration;
    }

    public ServiceConfiguration getServiceConfiguration() {
	return serviceConfiguration;
    }

    public PaymentsServiceConfiguration getPaymentsServiceConfiguration() {
	return paymentsService;
    }

    public ZkConfig getZkConfig() {
	return zkConfig;
    }

    public RemoteConfigConfiguration getRemoteConfigConfiguration() {
	return remoteConfig;
    }
}
