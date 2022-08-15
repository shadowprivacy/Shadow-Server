/*
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.shadowserver;

import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.jdbi3.strategies.DefaultNameStrategy;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.jetty.servlets.CrossOriginFilter;
import org.jdbi.v3.core.Jdbi;
import org.signal.zkgroup.ServerSecretParams;
import org.signal.zkgroup.auth.ServerZkAuthOperations;
import org.signal.zkgroup.profiles.ServerZkProfileOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import su.sres.websocket.WebSocketResourceProviderFactory;
import su.sres.websocket.setup.WebSocketEnvironment;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletRegistration;

import java.security.Security;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;
import io.dropwizard.Application;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.jersey.protobuf.ProtobufBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import io.minio.MinioClient;
import su.sres.dispatch.DispatchManager;
import su.sres.shadowserver.auth.AccountAuthenticator;
import su.sres.shadowserver.auth.CertificateGenerator;
// excluded federation, reserved for future purposes
// import su.sres.shadowserver.auth.FederatedPeerAuthenticator;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.DisabledPermittedAccountAuthenticator;
import su.sres.shadowserver.auth.TurnTokenGenerator;
import su.sres.shadowserver.controllers.*;
import su.sres.shadowserver.filters.TimestampResponseFilter;

// excluded federation, reserved for future purposes
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.FederatedPeer;

import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.liquibase.NameableMigrationsBundle;
import su.sres.shadowserver.mappers.DeviceLimitExceededExceptionMapper;
import su.sres.shadowserver.mappers.IOExceptionMapper;
import su.sres.shadowserver.mappers.InvalidWebsocketAddressExceptionMapper;
import su.sres.shadowserver.mappers.RateLimitExceededExceptionMapper;
import su.sres.shadowserver.metrics.BufferPoolGauges;
import su.sres.shadowserver.metrics.CpuUsageGauge;
import su.sres.shadowserver.metrics.FileDescriptorGauge;
import su.sres.shadowserver.metrics.FreeMemoryGauge;
import su.sres.shadowserver.metrics.GarbageCollectionGauges;
import su.sres.shadowserver.metrics.MaxFileDescriptorGauge;
import su.sres.shadowserver.metrics.MetricsApplicationEventListener;
import su.sres.shadowserver.metrics.NetworkReceivedGauge;
import su.sres.shadowserver.metrics.NetworkSentGauge;
import su.sres.shadowserver.metrics.OperatingSystemMemoryGauge;
import su.sres.shadowserver.metrics.PushLatencyManager;
import su.sres.shadowserver.metrics.TrafficSource;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.providers.RedisClusterHealthCheck;
import su.sres.shadowserver.providers.RedisHealthCheck;
import su.sres.shadowserver.push.ClientPresenceManager;
// import su.sres.shadowserver.push.APNSender;
// import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.GCMSender;
import su.sres.shadowserver.push.ProvisioningManager;
import su.sres.shadowserver.push.MessageSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.recaptcha.RecaptchaClient;
import su.sres.shadowserver.redis.ConnectionEventLogger;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.s3.PolicySigner;
import su.sres.shadowserver.s3.PostPolicyGenerator;
import su.sres.shadowserver.storage.*;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.ServerLicenseUtil;
import su.sres.shadowserver.util.ServerLicenseUtil.LicenseStatus;
import su.sres.shadowserver.websocket.AuthenticatedConnectListener;
import su.sres.shadowserver.websocket.DeadLetterHandler;
import su.sres.shadowserver.websocket.ProvisioningConnectListener;
import su.sres.shadowserver.websocket.WebSocketAccountAuthenticator;
import su.sres.shadowserver.workers.CertificateCommand;
import su.sres.shadowserver.workers.CreatePendingAccountCommand;
import su.sres.shadowserver.workers.CertHashCommand;
import su.sres.shadowserver.workers.DeleteUserCommand;
import su.sres.shadowserver.workers.DirectoryCommand;
import su.sres.shadowserver.workers.DisableRequestLoggingTask;
import su.sres.shadowserver.workers.EnableRequestLoggingTask;
import su.sres.shadowserver.workers.GetRedisCommandStatsCommand;
import su.sres.shadowserver.workers.GetRedisSlowlogCommand;
import su.sres.shadowserver.workers.LicenseHashCommand;
import su.sres.shadowserver.workers.VacuumCommand;
import su.sres.shadowserver.workers.ZkParamsCommand;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.dropwizard.auth.AuthFilter;
import io.dropwizard.auth.PolymorphicAuthDynamicFeature;
import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.auth.basic.BasicCredentials;

public class WhisperServerService extends Application<WhisperServerConfiguration> {

    static {
	Security.addProvider(new BouncyCastleProvider());
    }

    private final Logger logger = LoggerFactory.getLogger(WhisperServerService.class);

    @Override
    public void initialize(Bootstrap<WhisperServerConfiguration> bootstrap) {

	bootstrap.addCommand(new CertHashCommand());
	bootstrap.addCommand(new DirectoryCommand());
	bootstrap.addCommand(new LicenseHashCommand());
	bootstrap.addCommand(new VacuumCommand());
	bootstrap.addCommand(new CreatePendingAccountCommand());
	bootstrap.addCommand(new DeleteUserCommand());
	bootstrap.addCommand(new CertificateCommand());
	bootstrap.addCommand(new ZkParamsCommand());
	bootstrap.addCommand(new GetRedisSlowlogCommand());
	bootstrap.addCommand(new GetRedisCommandStatsCommand());

	bootstrap.addBundle(new ProtobufBundle<WhisperServerConfiguration>());

	bootstrap.addBundle(new NameableMigrationsBundle<WhisperServerConfiguration>("accountdb", "accountsdb.xml") {
	    @Override
	    public DataSourceFactory getDataSourceFactory(WhisperServerConfiguration configuration) {
		return configuration.getAccountsDatabaseConfiguration();
	    }
	});

	bootstrap.addBundle(new NameableMigrationsBundle<WhisperServerConfiguration>("messagedb", "messagedb.xml") {
	    @Override
	    public DataSourceFactory getDataSourceFactory(WhisperServerConfiguration configuration) {
		return configuration.getMessageStoreConfiguration();
	    }
	});

	bootstrap.addBundle(new NameableMigrationsBundle<WhisperServerConfiguration>("abusedb", "abusedb.xml") {
	    @Override
	    public PooledDataSourceFactory getDataSourceFactory(WhisperServerConfiguration configuration) {
		return configuration.getAbuseDatabaseConfiguration();
	    }
	});
    }

    @Override
    public String getName() {
	return "shadow-server";
    }

    @Override
    public void run(WhisperServerConfiguration config, Environment environment) throws Exception {

	SharedMetricRegistries.add(Constants.METRICS_NAME, environment.metrics());

	/*
	 * Metrics.addRegistry(new WavefrontMeterRegistry(new WavefrontConfig() {
	 * 
	 * @Override public String get(final String key) { return null; }
	 * 
	 * @Override public String uri() { return
	 * config.getMicrometerConfiguration().getUri(); }
	 * 
	 * @Override public String apiToken() { return
	 * config.getMicrometerConfiguration().getApiKey(); } }, Clock.SYSTEM) {
	 * 
	 * @Override protected DistributionStatisticConfig defaultHistogramConfig() {
	 * return DistributionStatisticConfig.builder() .percentiles(.75, .95, .99,
	 * .999) .build() .merge(super.defaultHistogramConfig()); } });
	 */

	environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	environment.getObjectMapper().setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
	environment.getObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

	JdbiFactory jdbiFactory = new JdbiFactory(DefaultNameStrategy.CHECK_EMPTY);
	Jdbi accountJdbi = jdbiFactory.build(environment, config.getAccountsDatabaseConfiguration(), "accountdb");

	// start validation

	Pair<LicenseStatus, Pair<Integer, Integer>> validationResult = ServerLicenseUtil.validate(config, accountJdbi);

	LicenseStatus status = validationResult.first();
	Integer volume = validationResult.second().first();

	if (volume == -1) {

	    switch (status) {

	    case ABSENT:
		logger.warn("License key is absent. Exiting.");
		break;
	    case CORRUPTED:
		logger.warn("License key is corrupted. Exiting.");
		break;
	    case TAMPERED:
		logger.warn("License key is invalid. Exiting.");
		break;
	    case EXPIRED:
		logger.warn("License key is expired. Exiting.");
		break;
	    case NYV:
		logger.warn("License key is not yet valid. Exiting.");
		break;
	    case IRRELEVANT:
		logger.warn("License key is irrelevant. Exiting.");
		break;
	    case UNSPECIFIED:
		logger.warn("Unspecified error while checking the License key. Please contact the technical support. Exiting.");
		break;
	    default:
		logger.warn("Exception while checking the License key. Please contact the technical support. Exiting.");
		break;
	    }

	    System.exit(0);

	} else {
	    if (status != LicenseStatus.OK) {
		if (status == LicenseStatus.OVERSUBSCRIBED) {
		    logger.warn(String.format("Oversubscription! Contact your distributor for expansion to be able to host more than %s accounts. Exiting.", volume));
		} else {
		    logger.warn("Unknown error while checking the License key. Please contact the technical support. Exiting.");
		}

		System.exit(0);
	    }
	}

	// end validation

	Jdbi messageJdbi = jdbiFactory.build(environment, config.getMessageStoreConfiguration(), "messagedb");
	Jdbi abuseJdbi = jdbiFactory.build(environment, config.getAbuseDatabaseConfiguration(), "abusedb");

	FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("accounts_database", accountJdbi, config.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
	FaultTolerantDatabase messageDatabase = new FaultTolerantDatabase("message_database", messageJdbi, config.getMessageStoreConfiguration().getCircuitBreakerConfiguration());
	FaultTolerantDatabase abuseDatabase = new FaultTolerantDatabase("abuse_database", abuseJdbi, config.getAbuseDatabaseConfiguration().getCircuitBreakerConfiguration());

	Accounts accounts = new Accounts(accountDatabase);
	PendingAccounts pendingAccounts = new PendingAccounts(accountDatabase);
	PendingDevices pendingDevices = new PendingDevices(accountDatabase);
	Usernames usernames = new Usernames(accountDatabase);
	ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
	Profiles profiles = new Profiles(accountDatabase);
	Keys keys = new Keys(accountDatabase);
	Messages messages = new Messages(messageDatabase);
	AbusiveHostRules abusiveHostRules = new AbusiveHostRules(abuseDatabase);
	RemoteConfigs remoteConfigs = new RemoteConfigs(accountDatabase);
	FeatureFlags featureFlags = new FeatureFlags(accountDatabase);

	RedisClientFactory pubSubClientFactory = new RedisClientFactory("pubsub_cache", config.getPubsubCacheConfiguration().getUrl(), config.getPubsubCacheConfiguration().getReplicaUrls(), config.getPubsubCacheConfiguration().getCircuitBreakerConfiguration());
	RedisClientFactory directoryClientFactory = new RedisClientFactory("directory_cache", config.getDirectoryConfiguration().getUrl(), config.getDirectoryConfiguration().getReplicaUrls(), config.getDirectoryConfiguration().getCircuitBreakerConfiguration());
//	RedisClientFactory pushSchedulerClientFactory = new RedisClientFactory("push_scheduler_cache", config.getPushScheduler().getUrl(), config.getPushScheduler().getReplicaUrls(), config.getPushScheduler().getCircuitBreakerConfiguration());

	ReplicatedJedisPool pubsubClient = pubSubClientFactory.getRedisClientPool();
	ReplicatedJedisPool directoryClient = directoryClientFactory.getRedisClientPool();
//	ReplicatedJedisPool pushSchedulerClient = pushSchedulerClientFactory.getRedisClientPool();	

	ClientResources redisClusterClientResources = ClientResources.builder().build();
	ConnectionEventLogger.logConnectionEvents(redisClusterClientResources);

	FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster", config.getCacheClusterConfiguration(), redisClusterClientResources);
	FaultTolerantRedisCluster messagesCacheCluster = new FaultTolerantRedisCluster("messages_cluster", config.getMessageCacheConfiguration().getRedisClusterConfiguration(), redisClusterClientResources);
	FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster", config.getMetricsClusterConfiguration(), redisClusterClientResources);

	BlockingQueue<Runnable> keyspaceNotificationDispatchQueue = new ArrayBlockingQueue<>(10_000);
	Metrics.gaugeCollectionSize(name(getClass(), "keyspaceNotificationDispatchQueueSize"), Collections.emptyList(), keyspaceNotificationDispatchQueue);

	ScheduledExecutorService recurringJobExecutor = environment.lifecycle().scheduledExecutorService(name(getClass(), "recurringJob-%d")).threads(2).build();
	ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle().executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(16).workQueue(keyspaceNotificationDispatchQueue).build();
	ExecutorService apnSenderExecutor = environment.lifecycle().executorService(name(getClass(), "apnSender-%d")).maxThreads(1).minThreads(1).build();
	ExecutorService gcmSenderExecutor = environment.lifecycle().executorService(name(getClass(), "gcmSender-%d")).maxThreads(1).minThreads(1).build();

	ClientPresenceManager clientPresenceManager = new ClientPresenceManager(messagesCacheCluster, recurringJobExecutor, keyspaceNotificationDispatchExecutor);

	DirectoryManager directory = new DirectoryManager(directoryClient);
	PendingAccountsManager pendingAccountsManager = new PendingAccountsManager(pendingAccounts, cacheCluster);
	PendingDevicesManager pendingDevicesManager = new PendingDevicesManager(pendingDevices, cacheCluster);
	UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
	// excluded federation, reserved for future purposes
	// FederatedClientManager federatedClientManager = new
	// FederatedClientManager(environment, config.getJerseyClientConfiguration(),
	// config.getFederationConfiguration());
	ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
	MessagesCache messagesCache = new MessagesCache(messagesCacheCluster, keyspaceNotificationDispatchExecutor);
	PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster);
	MessagesManager messagesManager = new MessagesManager(messages, messagesCache, pushLatencyManager);
	AccountsManager accountsManager = new AccountsManager(accounts, directory, cacheCluster, keys, messagesManager, usernamesManager, profilesManager);
	RemoteConfigsManager remoteConfigsManager = new RemoteConfigsManager(remoteConfigs);
	FeatureFlagsManager featureFlagsManager = new FeatureFlagsManager(featureFlags, recurringJobExecutor);
	DeadLetterHandler deadLetterHandler = new DeadLetterHandler(accountsManager, messagesManager);
	DispatchManager dispatchManager = new DispatchManager(pubSubClientFactory, Optional.of(deadLetterHandler));
	PubSubManager pubSubManager = new PubSubManager(pubsubClient, dispatchManager);
	// APNSender apnSender = new APNSender(apnSenderExecutor, accountsManager,
	// config.getApnConfiguration());
	GCMSender gcmSender = new GCMSender(gcmSenderExecutor, accountsManager, config.getGcmConfiguration().getApiKey());

// excluded federation, reserved for future purposes
	// FederatedPeerAuthenticator federatedPeerAuthenticator = new
	// FederatedPeerAuthenticator(config.getFederationConfiguration());

	RateLimiters rateLimiters = new RateLimiters(config.getLimitsConfiguration(), cacheCluster);
	ProvisioningManager provisioningManager = new ProvisioningManager(pubSubManager);

	AccountAuthenticator accountAuthenticator = new AccountAuthenticator(accountsManager);
	DisabledPermittedAccountAuthenticator disabledPermittedAccountAuthenticator = new DisabledPermittedAccountAuthenticator(accountsManager);

	ExternalServiceCredentialGenerator storageCredentialsGenerator = new ExternalServiceCredentialGenerator(config.getSecureStorageServiceConfiguration().getUserAuthenticationTokenSharedSecret(), new byte[0], false);
	ExternalServiceCredentialGenerator paymentsCredentialsGenerator = new ExternalServiceCredentialGenerator(config.getPaymentsServiceConfiguration().getUserAuthenticationTokenSharedSecret(), new byte[0], false);

//    ApnFallbackManager       apnFallbackManager  = new ApnFallbackManager(pushSchedulerClient, apnSender, accountsManager);

	MessageSender messageSender = new MessageSender(null, clientPresenceManager, messagesManager, gcmSender, null, config.getPushConfiguration().getQueueSize(), pushLatencyManager);
// excluded federation, reserved for future purposes
	// ReceiptSender receiptSender = new ReceiptSender(accountsManager, pushSender,
	// federatedClientManager);
	ReceiptSender receiptSender = new ReceiptSender(accountsManager, messageSender);
	TurnTokenGenerator turnTokenGenerator = new TurnTokenGenerator(config.getTurnConfiguration());
	RecaptchaClient recaptchaClient = new RecaptchaClient(config.getRecaptchaConfiguration().getSecret());

	MessagePersister messagePersister = new MessagePersister(messagesCache, messagesManager, accountsManager, Duration.ofMinutes(config.getMessageCacheConfiguration().getPersistDelayMinutes()));

	ActiveUserCounter activeUserCounter = new ActiveUserCounter(config.getMetricsFactory(), cacheCluster);
	AccountCleaner accountCleaner = new AccountCleaner(accountsManager);
	PushFeedbackProcessor pushFeedbackProcessor = new PushFeedbackProcessor(accountsManager);
	List<AccountDatabaseCrawlerListener> accountDatabaseCrawlerListeners = List.of(pushFeedbackProcessor, activeUserCounter, accountCleaner);

	AccountDatabaseCrawlerCache accountDatabaseCrawlerCache = new AccountDatabaseCrawlerCache(cacheCluster);
	AccountDatabaseCrawler accountDatabaseCrawler = new AccountDatabaseCrawler(accountsManager, accountDatabaseCrawlerCache, accountDatabaseCrawlerListeners, config.getAccountDatabaseCrawlerConfiguration().getChunkSize(), config.getAccountDatabaseCrawlerConfiguration().getChunkIntervalMs());

	// apnSender.setApnFallbackManager(apnFallbackManager);
	// environment.lifecycle().manage(apnFallbackManager);
	environment.lifecycle().manage(pubSubManager);
	environment.lifecycle().manage(messageSender);
	environment.lifecycle().manage(accountDatabaseCrawler);
	environment.lifecycle().manage(remoteConfigsManager);
	environment.lifecycle().manage(messagesCache);
	environment.lifecycle().manage(messagePersister);
	environment.lifecycle().manage(clientPresenceManager);
	environment.lifecycle().manage(featureFlagsManager);

	MinioClient minioClient = new MinioClient(config.getCdnConfiguration().getUri(), config.getCdnConfiguration().getAccessKey(), config.getCdnConfiguration().getAccessSecret());
	PostPolicyGenerator profileCdnPolicyGenerator = new PostPolicyGenerator(config.getCdnConfiguration().getRegion(), config.getCdnConfiguration().getBucket(), config.getCdnConfiguration().getAccessKey());
	PolicySigner profileCdnPolicySigner = new PolicySigner(config.getCdnConfiguration().getAccessSecret(), config.getCdnConfiguration().getRegion());

	ServerSecretParams zkSecretParams = new ServerSecretParams(config.getZkConfig().getServerSecret());
	ServerZkProfileOperations zkProfileOperations = new ServerZkProfileOperations(zkSecretParams);
	ServerZkAuthOperations zkAuthOperations = new ServerZkAuthOperations(zkSecretParams);
	boolean isZkEnabled = config.getZkConfig().isEnabled();

	/*
	 * excluded federation, reserved for future purposes
	 * 
	 * AttachmentController attachmentController = new
	 * AttachmentController(rateLimiters, federatedClientManager, urlSigner);
	 * KeysController keysController = new KeysController(rateLimiters, keys,
	 * accountsManager, federatedClientManager); MessageController messageController
	 * = new MessageController(rateLimiters, pushSender, receiptSender,
	 * accountsManager, messagesManager, federatedClientManager, null);
	 */
	AttachmentControllerV1 attachmentControllerV1 = new AttachmentControllerV1(rateLimiters, config.getAwsAttachmentsConfiguration().getAccessKey(), config.getAwsAttachmentsConfiguration().getAccessSecret(), config.getAwsAttachmentsConfiguration().getBucket());
	AttachmentControllerV2 attachmentControllerV2 = new AttachmentControllerV2(rateLimiters, config.getAwsAttachmentsConfiguration().getAccessKey(), config.getAwsAttachmentsConfiguration().getAccessSecret(), config.getAwsAttachmentsConfiguration().getRegion(),
		config.getAwsAttachmentsConfiguration().getBucket());
	DebugLogController debugLogController = new DebugLogController(rateLimiters, config.getDebugLogsConfiguration().getAccessKey(), config.getDebugLogsConfiguration().getAccessSecret(), config.getDebugLogsConfiguration().getRegion(), config.getDebugLogsConfiguration().getBucket());
	KeysController keysController = new KeysController(rateLimiters, keys, accountsManager);
	MessageController messageController = new MessageController(rateLimiters, messageSender, receiptSender, accountsManager, messagesManager, null);
	ProfileController profileController = new ProfileController(rateLimiters, accountsManager, profilesManager, usernamesManager, minioClient, profileCdnPolicyGenerator, profileCdnPolicySigner, config.getCdnConfiguration().getBucket(), zkProfileOperations, isZkEnabled);
	StickerController stickerController = new StickerController(rateLimiters, config.getCdnConfiguration().getAccessKey(), config.getCdnConfiguration().getAccessSecret(), config.getCdnConfiguration().getRegion(), config.getCdnConfiguration().getBucket());
	RemoteConfigController remoteConfigController = new RemoteConfigController(remoteConfigsManager, config.getRemoteConfigConfiguration().getAuthorizedTokens(), config.getRemoteConfigConfiguration().getGlobalConfig());
	FeatureFlagsController featureFlagsController = new FeatureFlagsController(featureFlagsManager, config.getFeatureFlagConfiguration().getAuthorizedTokens());

	/*
	 * excluded federation, reserved for future purposes
	 * 
	 * environment.jersey().register(new AuthDynamicFeature(new
	 * BasicCredentialAuthFilter.Builder<Account>()
	 * .setAuthenticator(deviceAuthenticator)
	 * 
	 * .setPrincipal(Account.class) .buildAuthFilter(), new
	 * BasicCredentialAuthFilter.Builder<FederatedPeer>()
	 * .setAuthenticator(federatedPeerAuthenticator)
	 * .setPrincipal(FederatedPeer.class) .buildAuthFilter()));
	 */
	// excluded federation (?), reserved for future purposes
	// environment.jersey().register(new AuthValueFactoryProvider.Binder());

	AuthFilter<BasicCredentials, Account> accountAuthFilter = new BasicCredentialAuthFilter.Builder<Account>().setAuthenticator(accountAuthenticator).buildAuthFilter();
	AuthFilter<BasicCredentials, DisabledPermittedAccount> disabledPermittedAccountAuthFilter = new BasicCredentialAuthFilter.Builder<DisabledPermittedAccount>().setAuthenticator(disabledPermittedAccountAuthenticator).buildAuthFilter();

	environment.jersey().register(new MetricsApplicationEventListener(TrafficSource.HTTP));
	environment.jersey().register(new PolymorphicAuthDynamicFeature<>(ImmutableMap.of(Account.class, accountAuthFilter, DisabledPermittedAccount.class, disabledPermittedAccountAuthFilter)));

	environment.jersey().register(new TimestampResponseFilter());

	environment.jersey().register(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(Account.class, DisabledPermittedAccount.class)));

	environment.jersey().register(new AccountController(pendingAccountsManager, accountsManager, usernamesManager, abusiveHostRules, rateLimiters, messagesManager, turnTokenGenerator, config.getTestDevices(), recaptchaClient, gcmSender
	// , apnSender
		, config.getLocalParametersConfiguration(), config.getServiceConfiguration()));
	environment.jersey().register(new DeviceController(pendingDevicesManager, accountsManager, messagesManager, rateLimiters, config.getMaxDevices(), config.getLocalParametersConfiguration().getVerificationCodeLifetime()));
	environment.jersey().register(new PlainDirectoryController(rateLimiters, accountsManager));

	// excluded federation (?), reserved for future purposes
	// environment.jersey().register(new FederationControllerV1(accountsManager,
	// attachmentController, messageController));
	// environment.jersey().register(new FederationControllerV2(accountsManager,
	// attachmentController, messageController, keysController));

	environment.jersey().register(new ProvisioningController(rateLimiters, provisioningManager));
	environment.jersey().register(new CertificateController(new CertificateGenerator(config.getDeliveryCertificate().getCertificate(), config.getDeliveryCertificate().getPrivateKey(), config.getDeliveryCertificate().getExpiresDays()), zkAuthOperations, isZkEnabled));

	environment.jersey().register(new SecureStorageController(storageCredentialsGenerator));
	environment.jersey().register(new PaymentsController(paymentsCredentialsGenerator));
	environment.jersey().register(attachmentControllerV1);
	environment.jersey().register(attachmentControllerV2);
	environment.jersey().register(debugLogController);
	environment.jersey().register(keysController);
	environment.jersey().register(messageController);
	environment.jersey().register(profileController);
	environment.jersey().register(stickerController);
//    environment.jersey().register(remoteConfigController);
	environment.jersey().register(featureFlagsController);

	///
	WebSocketEnvironment<Account> webSocketEnvironment = new WebSocketEnvironment<>(environment, config.getWebSocketConfiguration(), 90000);
	webSocketEnvironment.setAuthenticator(new WebSocketAccountAuthenticator(accountAuthenticator));
	webSocketEnvironment.setConnectListener(new AuthenticatedConnectListener(receiptSender, messagesManager, messageSender, null, clientPresenceManager));
	webSocketEnvironment.jersey().register(new MetricsApplicationEventListener(TrafficSource.WEBSOCKET));
	webSocketEnvironment.jersey().register(new KeepAliveController(clientPresenceManager));
	webSocketEnvironment.jersey().register(messageController);
	webSocketEnvironment.jersey().register(profileController);
	webSocketEnvironment.jersey().register(attachmentControllerV1);
	webSocketEnvironment.jersey().register(attachmentControllerV2);
	webSocketEnvironment.jersey().register(remoteConfigController);

	WebSocketEnvironment<Account> provisioningEnvironment = new WebSocketEnvironment<>(environment, webSocketEnvironment.getRequestLog(), 60000);
	provisioningEnvironment.setConnectListener(new ProvisioningConnectListener(pubSubManager));
	provisioningEnvironment.jersey().register(new MetricsApplicationEventListener(TrafficSource.WEBSOCKET));
	provisioningEnvironment.jersey().register(new KeepAliveController(clientPresenceManager));

	registerCorsFilter(environment);
	registerExceptionMappers(environment, webSocketEnvironment, provisioningEnvironment);

	WebSocketResourceProviderFactory<Account> webSocketServlet = new WebSocketResourceProviderFactory<>(webSocketEnvironment, Account.class);
	WebSocketResourceProviderFactory<Account> provisioningServlet = new WebSocketResourceProviderFactory<>(provisioningEnvironment, Account.class);

	ServletRegistration.Dynamic websocket = environment.servlets().addServlet("WebSocket", webSocketServlet);
	ServletRegistration.Dynamic provisioning = environment.servlets().addServlet("Provisioning", provisioningServlet);

	websocket.addMapping("/v1/websocket/");
	websocket.setAsyncSupported(true);

	provisioning.addMapping("/v1/websocket/provisioning/");
	provisioning.setAsyncSupported(true);

	environment.admin().addTask(new EnableRequestLoggingTask());
	environment.admin().addTask(new DisableRequestLoggingTask());

///

	environment.healthChecks().register("directory", new RedisHealthCheck(directoryClient));
	environment.healthChecks().register("cacheCluster", new RedisClusterHealthCheck(cacheCluster));

	environment.metrics().register(name(CpuUsageGauge.class, "cpu"), new CpuUsageGauge(3, TimeUnit.SECONDS));
	environment.metrics().register(name(FreeMemoryGauge.class, "free_memory"), new FreeMemoryGauge());
	environment.metrics().register(name(NetworkSentGauge.class, "bytes_sent"), new NetworkSentGauge());
	environment.metrics().register(name(NetworkReceivedGauge.class, "bytes_received"), new NetworkReceivedGauge());
	environment.metrics().register(name(FileDescriptorGauge.class, "fd_count"), new FileDescriptorGauge());
	environment.metrics().register(name(MaxFileDescriptorGauge.class, "max_fd_count"), new MaxFileDescriptorGauge());
	environment.metrics().register(name(OperatingSystemMemoryGauge.class, "buffers"), new OperatingSystemMemoryGauge("Buffers"));
	environment.metrics().register(name(OperatingSystemMemoryGauge.class, "cached"), new OperatingSystemMemoryGauge("Cached"));

	BufferPoolGauges.registerMetrics();
	GarbageCollectionGauges.registerMetrics();
    }

    private void registerExceptionMappers(Environment environment, WebSocketEnvironment<Account> webSocketEnvironment, WebSocketEnvironment<Account> provisioningEnvironment) {
	environment.jersey().register(new IOExceptionMapper());
	environment.jersey().register(new RateLimitExceededExceptionMapper());
	environment.jersey().register(new InvalidWebsocketAddressExceptionMapper());
	environment.jersey().register(new DeviceLimitExceededExceptionMapper());

	webSocketEnvironment.jersey().register(new IOExceptionMapper());
	webSocketEnvironment.jersey().register(new RateLimitExceededExceptionMapper());
	webSocketEnvironment.jersey().register(new InvalidWebsocketAddressExceptionMapper());
	webSocketEnvironment.jersey().register(new DeviceLimitExceededExceptionMapper());

	provisioningEnvironment.jersey().register(new IOExceptionMapper());
	provisioningEnvironment.jersey().register(new RateLimitExceededExceptionMapper());
	provisioningEnvironment.jersey().register(new InvalidWebsocketAddressExceptionMapper());
	provisioningEnvironment.jersey().register(new DeviceLimitExceededExceptionMapper());
    }

    private void registerCorsFilter(Environment environment) {
	FilterRegistration.Dynamic filter = environment.servlets().addFilter("CORS", CrossOriginFilter.class);
	filter.addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, "/*");
	filter.setInitParameter("allowedOrigins", "*");
	filter.setInitParameter("allowedHeaders", "Content-Type,Authorization,X-Requested-With,Content-Length,Accept,Origin,X-Signal-Agent");
	filter.setInitParameter("allowedMethods", "GET,PUT,POST,DELETE,OPTIONS");
	filter.setInitParameter("preflightMaxAge", "5184000");
	filter.setInitParameter("allowCredentials", "true");
    }

    public static void main(String[] args) throws Exception {
	new WhisperServerService().run(args);
    }
}
