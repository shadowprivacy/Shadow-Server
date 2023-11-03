/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver;

import static com.codahale.metrics.MetricRegistry.name;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.jdbi3.strategies.DefaultNameStrategy;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

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

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import io.dropwizard.Application;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.jdbi3.JdbiFactory;
import io.dropwizard.jersey.protobuf.ProtobufBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.minio.MinioClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import su.sres.dispatch.DispatchManager;
import su.sres.shadowserver.auth.AccountAuthenticator;
import su.sres.shadowserver.auth.WebsocketRefreshApplicationEventListener;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.CertificateGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.GroupUser;
import su.sres.shadowserver.auth.GroupUserAuthenticator;
import su.sres.shadowserver.auth.DisabledPermittedAccountAuthenticator;
import su.sres.shadowserver.auth.DisabledPermittedAuthenticatedAccount;
import su.sres.shadowserver.auth.ExternalGroupCredentialGenerator;
import su.sres.shadowserver.auth.TurnTokenGenerator;
import su.sres.shadowserver.badges.ConfiguredProfileBadgeConverter;
import su.sres.shadowserver.badges.ProfileBadgeConverter;
import su.sres.shadowserver.configuration.AccountsScyllaDbConfiguration;
import su.sres.shadowserver.configuration.LocalParametersConfiguration;
import su.sres.shadowserver.configuration.MessageScyllaDbConfiguration;
import su.sres.shadowserver.configuration.MinioConfiguration;
import su.sres.shadowserver.configuration.ScyllaDbConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicConfiguration;
import su.sres.shadowserver.controllers.*;
import su.sres.shadowserver.currency.CurrencyConversionManager;
import su.sres.shadowserver.currency.FixerClient;
import su.sres.shadowserver.currency.FtxClient;
import su.sres.shadowserver.filters.ContentLengthFilter;
import su.sres.shadowserver.filters.RemoteDeprecationFilter;
import su.sres.shadowserver.filters.TimestampResponseFilter;
import su.sres.shadowserver.limits.PreKeyRateLimiter;
import su.sres.shadowserver.limits.PushChallengeManager;
import su.sres.shadowserver.limits.RateLimitChallengeManager;
import su.sres.shadowserver.limits.RateLimitResetMetricsManager;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.limits.UnsealedSenderRateLimiter;
import su.sres.shadowserver.liquibase.NameableMigrationsBundle;
import su.sres.shadowserver.mappers.DeviceLimitExceededExceptionMapper;
import su.sres.shadowserver.mappers.IOExceptionMapper;
import su.sres.shadowserver.mappers.InvalidWebsocketAddressExceptionMapper;
import su.sres.shadowserver.mappers.RateLimitChallengeExceptionMapper;
import su.sres.shadowserver.mappers.RateLimitExceededExceptionMapper;
import su.sres.shadowserver.mappers.RetryLaterExceptionMapper;
import su.sres.shadowserver.mappers.ServerRejectedExceptionMapper;
import su.sres.shadowserver.metrics.ApplicationShutdownMonitor;
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
import su.sres.shadowserver.providers.InvalidProtocolBufferExceptionMapper;
import su.sres.shadowserver.providers.MultiRecipientMessageProvider;
import su.sres.shadowserver.providers.ProtocolBufferMessageBodyProvider;
import su.sres.shadowserver.providers.ProtocolBufferValidationErrorMessageBodyWriter;
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
import su.sres.shadowserver.recaptcha.EnterpriseRecaptchaClient;
import su.sres.shadowserver.recaptcha.LegacyRecaptchaClient;
import su.sres.shadowserver.recaptcha.TransitionalRecaptchaClient;
import su.sres.shadowserver.redis.ConnectionEventLogger;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.s3.PolicySigner;
import su.sres.shadowserver.s3.PostPolicyGenerator;
import su.sres.shadowserver.storage.*;
import su.sres.shadowserver.util.AsnManager;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.ScyllaDbFromConfig;
import su.sres.shadowserver.util.ServerLicenseUtil;
import su.sres.shadowserver.util.ServerLicenseUtil.LicenseStatus;
import su.sres.shadowserver.util.TorExitNodeManager;
import su.sres.shadowserver.util.logging.LoggingUnhandledExceptionMapper;
import su.sres.shadowserver.util.logging.UncaughtExceptionHandler;
import su.sres.shadowserver.websocket.AuthenticatedConnectListener;
import su.sres.shadowserver.websocket.DeadLetterHandler;
import su.sres.shadowserver.websocket.ProvisioningConnectListener;
import su.sres.shadowserver.websocket.WebSocketAccountAuthenticator;
import su.sres.shadowserver.workers.CertificateCommand;
import su.sres.shadowserver.workers.CreateAccountsDbCommand;
import su.sres.shadowserver.workers.CreateDeletedAccountsDbCommand;
import su.sres.shadowserver.workers.CreateGroupDbCommand;
import su.sres.shadowserver.workers.CreateGroupLogsDbCommand;
import su.sres.shadowserver.workers.CreateKeysDbCommand;
import su.sres.shadowserver.workers.CreateMessageDbCommand;
import su.sres.shadowserver.workers.CreatePendingAccountCommand;
import su.sres.shadowserver.workers.CreatePushChallengeDbCommand;
import su.sres.shadowserver.workers.CreateReportMessageDbCommand;
import su.sres.shadowserver.workers.CreatePendingsDbCommand;
import su.sres.shadowserver.workers.DeleteUserCommand;
import su.sres.shadowserver.workers.DirectoryCommand;
import su.sres.shadowserver.workers.GenerateQRCodeCommand;
import su.sres.shadowserver.workers.LicenseHashCommand;
import su.sres.shadowserver.workers.ServerVersionCommand;
import su.sres.shadowserver.workers.SetCrawlerAccelerationTask;
import su.sres.shadowserver.workers.SetRequestLoggingEnabledTask;
import su.sres.shadowserver.workers.ShowLicenseCommand;
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

  private static final Logger logger = LoggerFactory.getLogger(WhisperServerService.class);

  @Override
  public void initialize(Bootstrap<WhisperServerConfiguration> bootstrap) {

    bootstrap.addCommand(new CertificateCommand());
    bootstrap.addCommand(new CreateAccountsDbCommand());
    bootstrap.addCommand(new CreateDeletedAccountsDbCommand());
    bootstrap.addCommand(new CreateGroupDbCommand());
    bootstrap.addCommand(new CreateGroupLogsDbCommand());
    bootstrap.addCommand(new CreateKeysDbCommand());
    bootstrap.addCommand(new CreateMessageDbCommand());    
    bootstrap.addCommand(new CreatePendingAccountCommand());
    bootstrap.addCommand(new CreatePendingsDbCommand());
    bootstrap.addCommand(new CreatePushChallengeDbCommand());
    bootstrap.addCommand(new CreateReportMessageDbCommand());
    bootstrap.addCommand(new DeleteUserCommand());
    bootstrap.addCommand(new DirectoryCommand());
    bootstrap.addCommand(new GenerateQRCodeCommand());
    bootstrap.addCommand(new LicenseHashCommand());
    bootstrap.addCommand(new ServerVersionCommand());
    bootstrap.addCommand(new ShowLicenseCommand());
    bootstrap.addCommand(new VacuumCommand());
    bootstrap.addCommand(new ZkParamsCommand());

    bootstrap.addBundle(new ProtobufBundle<WhisperServerConfiguration>());

    bootstrap.addBundle(new NameableMigrationsBundle<WhisperServerConfiguration>("accountdb", "accountsdb.xml") {
      @Override
      public DataSourceFactory getDataSourceFactory(WhisperServerConfiguration configuration) {
        return configuration.getAccountsDatabaseConfiguration();
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
    
    final Clock clock = Clock.systemUTC();

    UncaughtExceptionHandler.register();

    SharedMetricRegistries.add(Constants.METRICS_NAME, environment.metrics());

    final DistributionStatisticConfig defaultDistributionStatisticConfig = DistributionStatisticConfig.builder()
        .percentiles(.75, .95, .99, .999)
        .build();

    /*
     * {
     * 
     * final DatadogMeterRegistry datadogMeterRegistry = new DatadogMeterRegistry(
     * config.getDatadogConfiguration(),
     * io.micrometer.core.instrument.Clock.SYSTEM);
     * 
     * datadogMeterRegistry.config().commonTags( Tags.of( "service", "chat", "host",
     * HostnameUtil.getLocalHostname(), "version",
     * WhisperServerVersion.getServerVersion(), "env",
     * config.getDatadogConfiguration().getEnvironment()))
     * .meterFilter(MeterFilter.denyNameStartsWith(MetricsRequestEventListener.
     * REQUEST_COUNTER_NAME))
     * .meterFilter(MeterFilter.denyNameStartsWith(MetricsRequestEventListener.
     * ANDROID_REQUEST_COUNTER_NAME))
     * .meterFilter(MeterFilter.denyNameStartsWith(MetricsRequestEventListener.
     * DESKTOP_REQUEST_COUNTER_NAME))
     * .meterFilter(MeterFilter.denyNameStartsWith(MetricsRequestEventListener.
     * IOS_REQUEST_COUNTER_NAME)) .meterFilter(new MeterFilter() {
     * 
     * @Override public DistributionStatisticConfig configure(final Id id, final
     * DistributionStatisticConfig config) { return
     * defaultDistributionStatisticConfig.merge(config); } });
     * 
     * Metrics.addRegistry(datadogMeterRegistry); }
     */

    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    environment.getObjectMapper().setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
    environment.getObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

    ProfileBadgeConverter profileBadgeConverter = new ConfiguredProfileBadgeConverter(Clock.systemUTC(), config.getBadges());

    JdbiFactory jdbiFactory = new JdbiFactory(DefaultNameStrategy.CHECK_EMPTY);
    Jdbi accountJdbi = jdbiFactory.build(environment, config.getAccountsDatabaseConfiguration(), "accountdb");

    // start validation

    Pair<LicenseStatus, Pair<Integer, Integer>> validationResult = ServerLicenseUtil.validate(config);

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

    final MinioConfiguration minioConfig = config.getMinioConfiguration();

    Jdbi abuseJdbi = jdbiFactory.build(environment, config.getAbuseDatabaseConfiguration(), "abusedb");

    FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("accounts_database", accountJdbi, config.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
    FaultTolerantDatabase abuseDatabase = new FaultTolerantDatabase("abuse_database", abuseJdbi, config.getAbuseDatabaseConfiguration().getCircuitBreakerConfiguration());

    LocalParametersConfiguration localParams = config.getLocalParametersConfiguration();
    MessageScyllaDbConfiguration scyllaMessageConfig = config.getMessageScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaKeysConfig = config.getKeysScyllaDbConfiguration();
    AccountsScyllaDbConfiguration scyllaAccountsConfig = config.getAccountsScyllaDbConfiguration();    
    ScyllaDbConfiguration scyllaPushChallengeConfig = config.getPushChallengeScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaReportMessageConfig = config.getReportMessageScyllaDbConfiguration();    
    ScyllaDbConfiguration scyllaPendingAccountsConfig = config.getPendingAccountsScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaPendingDevicesConfig = config.getPendingDevicesScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaDeletedAccountsConfig = config.getDeletedAccountsScyllaDbConfiguration();

    ScyllaDbConfiguration scyllaGroupsConfig = config.getGroupsScyllaDbConfiguration();
    ScyllaDbConfiguration scyllaGroupLogsConfig = config.getGroupLogsScyllaDbConfiguration();

    DynamoDbClient messageScyllaDb = ScyllaDbFromConfig.client(scyllaMessageConfig);

    DynamoDbClient preKeyScyllaDb = ScyllaDbFromConfig.client(scyllaKeysConfig);

    DynamoDbClient accountsClient = ScyllaDbFromConfig.client(scyllaAccountsConfig);

    AmazonDynamoDBClientBuilder groupsScyllaDbClientBuilder = AmazonDynamoDBClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(scyllaGroupsConfig.getEndpoint(), scyllaGroupsConfig.getRegion()))
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(((int) scyllaGroupsConfig.getClientExecutionTimeout().toMillis()))
            .withRequestTimeout((int) scyllaGroupsConfig.getClientRequestTimeout().toMillis()))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(scyllaGroupsConfig.getAccessKey(), scyllaGroupsConfig.getAccessSecret())));

    AmazonDynamoDBClientBuilder groupLogsScyllaDbClientBuilder = AmazonDynamoDBClientBuilder
        .standard()
        .withEndpointConfiguration(new EndpointConfiguration(scyllaGroupLogsConfig.getEndpoint(), scyllaGroupLogsConfig.getRegion()))
        .withClientConfiguration(new ClientConfiguration().withClientExecutionTimeout(((int) scyllaGroupLogsConfig.getClientExecutionTimeout().toMillis()))
            .withRequestTimeout((int) scyllaGroupLogsConfig.getClientRequestTimeout().toMillis()))
        .withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(scyllaGroupLogsConfig.getAccessKey(), scyllaGroupLogsConfig.getAccessSecret())));
    
    DynamoDbClient deletedAccountsScyllaDbClient = ScyllaDbFromConfig.client(scyllaDeletedAccountsConfig);
    
    DynamoDbClient pushChallengeScyllaDbClient = ScyllaDbFromConfig.client(scyllaPushChallengeConfig);

    DynamoDbClient reportMessageScyllaDbClient = ScyllaDbFromConfig.client(scyllaReportMessageConfig);
    
    DynamoDbClient pendingAccountsScyllaDbClient = ScyllaDbFromConfig.client(scyllaPendingAccountsConfig);

    DynamoDbClient pendingDevicesScyllaDbClient = ScyllaDbFromConfig.client(scyllaPendingDevicesConfig);

    DynamoDB groupsDynamoDb = new DynamoDB(groupsScyllaDbClientBuilder.build());
    DynamoDB groupLogsDynamoDb = new DynamoDB(groupLogsScyllaDbClientBuilder.build());

    DeletedAccounts deletedAccounts = new DeletedAccounts(deletedAccountsScyllaDbClient, scyllaDeletedAccountsConfig.getTableName());
    
    Accounts accounts = new Accounts(accountsClient, scyllaAccountsConfig.getTableName(), scyllaAccountsConfig.getUserLoginTableName(), scyllaAccountsConfig.getMiscTableName(), scyllaAccountsConfig.getScanPageSize());
    
    Usernames usernames = new Usernames(accountDatabase);
    ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
    Profiles profiles = new Profiles(accountDatabase);
    KeysScyllaDb keysScyllaDb = new KeysScyllaDb(preKeyScyllaDb, scyllaKeysConfig.getTableName());
    MessagesScyllaDb messagesScyllaDb = new MessagesScyllaDb(messageScyllaDb, scyllaMessageConfig.getTableName(), scyllaMessageConfig.getTimeToLive());
    GroupsScyllaDb groupsScyllaDb = new GroupsScyllaDb(groupsDynamoDb, scyllaGroupsConfig.getTableName());
    GroupLogsScyllaDb groupLogsScyllaDb = new GroupLogsScyllaDb(groupLogsDynamoDb, scyllaGroupLogsConfig.getTableName());
    AbusiveHostRules abusiveHostRules = new AbusiveHostRules(abuseDatabase);
    RemoteConfigs remoteConfigs = new RemoteConfigs(accountDatabase);

    PushChallengeScyllaDb pushChallengeScyllaDb = new PushChallengeScyllaDb(pushChallengeScyllaDbClient, scyllaPushChallengeConfig.getTableName());
    ReportMessageScyllaDb reportMessageScyllaDb = new ReportMessageScyllaDb(reportMessageScyllaDbClient, scyllaReportMessageConfig.getTableName());

    VerificationCodeStore pendingAccounts = new VerificationCodeStore(pendingAccountsScyllaDbClient, scyllaPendingAccountsConfig.getTableName());
    VerificationCodeStore pendingDevices = new VerificationCodeStore(pendingDevicesScyllaDbClient, scyllaPendingDevicesConfig.getTableName());

    RedisClientFactory pubSubClientFactory = new RedisClientFactory("pubsub_cache", config.getPubsubCacheConfiguration().getUrl(), config.getPubsubCacheConfiguration().getReplicaUrls(), config.getPubsubCacheConfiguration().getCircuitBreakerConfiguration());
    RedisClientFactory directoryClientFactory = new RedisClientFactory("directory_cache", config.getDirectoryConfiguration().getUrl(), config.getDirectoryConfiguration().getReplicaUrls(), config.getDirectoryConfiguration().getCircuitBreakerConfiguration());

    ReplicatedJedisPool pubsubClient = pubSubClientFactory.getRedisClientPool();
    ReplicatedJedisPool directoryClient = directoryClientFactory.getRedisClientPool();

    ClientResources generalCacheClientResources = ClientResources.builder().build();
    ClientResources messageCacheClientResources = ClientResources.builder().build();
    ClientResources presenceClientResources = ClientResources.builder().build();
    ClientResources metricsCacheClientResources = ClientResources.builder().build();
    // ClientResources pushSchedulerCacheClientResources =
    // ClientResources.builder().ioThreadPoolSize(4).build();
    ClientResources rateLimitersCacheClientResources = ClientResources.builder().build();

    ConnectionEventLogger.logConnectionEvents(generalCacheClientResources);
    ConnectionEventLogger.logConnectionEvents(messageCacheClientResources);
    ConnectionEventLogger.logConnectionEvents(presenceClientResources);
    ConnectionEventLogger.logConnectionEvents(metricsCacheClientResources);

    FaultTolerantRedisCluster cacheCluster = new FaultTolerantRedisCluster("main_cache_cluster", config.getCacheClusterConfiguration(), generalCacheClientResources);
    FaultTolerantRedisCluster messagesCluster = new FaultTolerantRedisCluster("messages_cluster", config.getMessageCacheConfiguration().getRedisClusterConfiguration(), messageCacheClientResources);
    FaultTolerantRedisCluster clientPresenceCluster = new FaultTolerantRedisCluster("client_presence_cluster", config.getClientPresenceClusterConfiguration(), presenceClientResources);
    FaultTolerantRedisCluster metricsCluster = new FaultTolerantRedisCluster("metrics_cluster", config.getMetricsClusterConfiguration(), metricsCacheClientResources);
    // disabled until iOS version is in place
    // FaultTolerantRedisCluster pushSchedulerCluster = new
    // FaultTolerantRedisCluster("push_scheduler", config.getPushSchedulerCluster(),
    // pushSchedulerCacheClientResources);
    FaultTolerantRedisCluster rateLimitersCluster = new FaultTolerantRedisCluster("rate_limiters", config.getRateLimitersCluster(), rateLimitersCacheClientResources);

    BlockingQueue<Runnable> keyspaceNotificationDispatchQueue = new ArrayBlockingQueue<>(10_000);
    Metrics.gaugeCollectionSize(name(getClass(), "keyspaceNotificationDispatchQueueSize"), Collections.emptyList(), keyspaceNotificationDispatchQueue);

    ScheduledExecutorService recurringJobExecutor = environment.lifecycle().scheduledExecutorService(name(getClass(), "recurringJob-%d")).threads(6).build();
    ScheduledExecutorService declinedMessageReceiptExecutor = environment.lifecycle().scheduledExecutorService(name(getClass(), "declined-receipt-%d")).threads(2).build();
    ScheduledExecutorService retrySchedulingExecutor = environment.lifecycle().scheduledExecutorService(name(getClass(), "retry-%d")).threads(2).build();
    ExecutorService keyspaceNotificationDispatchExecutor = environment.lifecycle().executorService(name(getClass(), "keyspaceNotification-%d")).maxThreads(16).workQueue(keyspaceNotificationDispatchQueue).build();
    // ExecutorService apnSenderExecutor =
    // environment.lifecycle().executorService(name(getClass(),
    // "apnSender-%d")).maxThreads(1).minThreads(1).build();
    ExecutorService gcmSenderExecutor = environment.lifecycle().executorService(name(getClass(), "gcmSender-%d")).maxThreads(1).minThreads(1).build();
    ExecutorService multiRecipientMessageExecutor = environment.lifecycle().executorService(name(getClass(), "multiRecipientMessage-%d")).minThreads(64).maxThreads(64).build();
    
    ClientPresenceManager clientPresenceManager = new ClientPresenceManager(clientPresenceCluster, recurringJobExecutor, keyspaceNotificationDispatchExecutor);

    DynamicConfiguration dynamicConfig = new DynamicConfiguration();
    DirectoryManager directory = new DirectoryManager(directoryClient);
    final int lifetime = localParams.getVerificationCodeLifetime();
    StoredVerificationCodeManager pendingAccountsManager = new StoredVerificationCodeManager(pendingAccounts, lifetime);
    StoredVerificationCodeManager pendingDevicesManager = new StoredVerificationCodeManager(pendingDevices, lifetime);
    UsernamesManager usernamesManager = new UsernamesManager(usernames, reservedUsernames, cacheCluster);
    ProfilesManager profilesManager = new ProfilesManager(profiles, cacheCluster);
    MessagesCache messagesCache = new MessagesCache(messagesCluster, messagesCluster, keyspaceNotificationDispatchExecutor);
    PushLatencyManager pushLatencyManager = new PushLatencyManager(metricsCluster);
    ReportMessageManager reportMessageManager = new ReportMessageManager(reportMessageScyllaDb, Metrics.globalRegistry);
    MessagesManager messagesManager = new MessagesManager(messagesScyllaDb, messagesCache, pushLatencyManager, reportMessageManager);
    AccountsManager accountsManager = new AccountsManager(accounts, directory, cacheCluster, deletedAccounts, keysScyllaDb, messagesManager, usernamesManager, profilesManager, pendingAccountsManager, clientPresenceManager);
    RemoteConfigsManager remoteConfigsManager = new RemoteConfigsManager(remoteConfigs);
    DeadLetterHandler deadLetterHandler = new DeadLetterHandler(accountsManager, messagesManager);
    DispatchManager dispatchManager = new DispatchManager(pubSubClientFactory, Optional.of(deadLetterHandler));
    PubSubManager pubSubManager = new PubSubManager(pubsubClient, dispatchManager);
    // APNSender apnSender = new APNSender(apnSenderExecutor, accountsManager,
    // config.getApnConfiguration());
    GCMSender gcmSender = new GCMSender(gcmSenderExecutor, accountsManager, config.getGcmConfiguration().getApiKey());

    RateLimiters rateLimiters = new RateLimiters(config.getLimitsConfiguration(), dynamicConfig.getLimits(), rateLimitersCluster);
    ProvisioningManager provisioningManager = new ProvisioningManager(pubSubManager);
    TorExitNodeManager torExitNodeManager = new TorExitNodeManager(recurringJobExecutor, minioConfig);
    AsnManager asnManager = new AsnManager(recurringJobExecutor, minioConfig);

    AccountAuthenticator accountAuthenticator = new AccountAuthenticator(accountsManager);
    DisabledPermittedAccountAuthenticator disabledPermittedAccountAuthenticator = new DisabledPermittedAccountAuthenticator(accountsManager);

    RateLimitResetMetricsManager rateLimitResetMetricsManager = new RateLimitResetMetricsManager(metricsCluster, Metrics.globalRegistry);

    UnsealedSenderRateLimiter unsealedSenderRateLimiter = new UnsealedSenderRateLimiter(rateLimiters, rateLimitersCluster, dynamicConfig, rateLimitResetMetricsManager);
    PreKeyRateLimiter preKeyRateLimiter = new PreKeyRateLimiter(rateLimiters, dynamicConfig.getRateLimitChallengeConfiguration(), rateLimitResetMetricsManager);

    ExternalServiceCredentialGenerator storageCredentialsGenerator = new ExternalServiceCredentialGenerator(config.getSecureStorageServiceConfiguration().getUserAuthenticationTokenSharedSecret(), new byte[0], false);
    ExternalServiceCredentialGenerator paymentsCredentialsGenerator = new ExternalServiceCredentialGenerator(config.getPaymentsServiceConfiguration().getUserAuthenticationTokenSharedSecret(), new byte[0], false);

//    ApnFallbackManager       apnFallbackManager = new ApnFallbackManager(pushSchedulerCluster, apnSender, accountsManager);

    MessageSender messageSender = new MessageSender(null, clientPresenceManager, messagesManager, gcmSender, null, pushLatencyManager);
    ReceiptSender receiptSender = new ReceiptSender(accountsManager, messageSender);
    TurnTokenGenerator turnTokenGenerator = new TurnTokenGenerator(config.getTurnConfiguration());
    LegacyRecaptchaClient legacyRecaptchaClient = new LegacyRecaptchaClient(config.getRecaptchaConfiguration().getSecret());
    EnterpriseRecaptchaClient enterpriseRecaptchaClient = new EnterpriseRecaptchaClient(
        config.getRecaptchaV2Configuration().getScoreFloor().doubleValue(),
        config.getRecaptchaV2Configuration().getSiteKey(),
        config.getRecaptchaV2Configuration().getProjectPath(),
        config.getRecaptchaV2Configuration().getCredentialConfigurationJson());
    TransitionalRecaptchaClient transitionalRecaptchaClient = new TransitionalRecaptchaClient(legacyRecaptchaClient, enterpriseRecaptchaClient);

    PushChallengeManager pushChallengeManager = new PushChallengeManager(
        // apnSender,
        gcmSender, pushChallengeScyllaDb);
    RateLimitChallengeManager rateLimitChallengeManager = new RateLimitChallengeManager(pushChallengeManager, transitionalRecaptchaClient, preKeyRateLimiter, unsealedSenderRateLimiter, rateLimiters, dynamicConfig.getRateLimitChallengeConfiguration());

    MessagePersister messagePersister = new MessagePersister(messagesCache, messagesManager, accountsManager, dynamicConfig, Duration.ofMinutes(config.getMessageCacheConfiguration().getPersistDelayMinutes()));

    // TODO listeners must be ordered so that ones that directly update accounts
    // come last, so that read-only ones are not working with stale data
    final List<AccountDatabaseCrawlerListener> accountDatabaseCrawlerListeners = new ArrayList<>();

    // PushFeedbackProcessor may update device properties
    accountDatabaseCrawlerListeners.add(new PushFeedbackProcessor(accountsManager));

    // delete accounts last
    if (localParams.getAccountExpirationPolicy() != 0)
      accountDatabaseCrawlerListeners.add(new AccountCleaner(accountsManager, localParams.getAccountExpirationPolicy(), localParams.getAccountLifetime()));

    HttpClient currencyClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build();
    FixerClient fixerClient = new FixerClient(currencyClient, config.getPaymentsServiceConfiguration().getFixerApiKey());
    FtxClient ftxClient = new FtxClient(currencyClient);
    CurrencyConversionManager currencyManager = new CurrencyConversionManager(fixerClient, ftxClient, config.getPaymentsServiceConfiguration().getPaymentCurrencies());

    AccountDatabaseCrawlerCache accountDatabaseCrawlerCache = new AccountDatabaseCrawlerCache(cacheCluster);
    AccountDatabaseCrawler accountDatabaseCrawler = new AccountDatabaseCrawler(accountsManager, accountDatabaseCrawlerCache, accountDatabaseCrawlerListeners, config.getAccountDatabaseCrawlerConfiguration().getChunkSize(), config.getAccountDatabaseCrawlerConfiguration().getChunkIntervalMs());
       
    // apnSender.setApnFallbackManager(apnFallbackManager);
    environment.lifecycle().manage(new ApplicationShutdownMonitor());
    // environment.lifecycle().manage(apnFallbackManager);
    environment.lifecycle().manage(pubSubManager);
    environment.lifecycle().manage(messageSender);
    environment.lifecycle().manage(accountDatabaseCrawler);    
    environment.lifecycle().manage(remoteConfigsManager);
    environment.lifecycle().manage(messagesCache);
    environment.lifecycle().manage(messagePersister);
    environment.lifecycle().manage(clientPresenceManager);
    environment.lifecycle().manage(currencyManager);
    environment.lifecycle().manage(torExitNodeManager);
    environment.lifecycle().manage(asnManager);

    MinioClient minioClient = MinioClient.builder()
        .endpoint(minioConfig.getUri())
        .credentials(minioConfig.getAccessKey(), minioConfig.getAccessSecret())
        .build();

    PostPolicyGenerator profileCdnPolicyGenerator = new PostPolicyGenerator(minioConfig.getRegion(), minioConfig.getProfileBucket(), minioConfig.getAccessKey());
    PolicySigner profileCdnPolicySigner = new PolicySigner(minioConfig.getAccessSecret(), minioConfig.getRegion());

    ServerSecretParams zkSecretParams = new ServerSecretParams(config.getZkConfig().getServerSecret());
    ServerZkProfileOperations zkProfileOperations = new ServerZkProfileOperations(zkSecretParams);
    ServerZkAuthOperations zkAuthOperations = new ServerZkAuthOperations(zkSecretParams);    

    GroupsManager groupsManager = new GroupsManager(groupsScyllaDb, groupLogsScyllaDb);

    GroupUserAuthenticator groupUserAuthenticator = new GroupUserAuthenticator(new ServerZkAuthOperations(zkSecretParams));
    ExternalGroupCredentialGenerator externalGroupCredentialGenerator = new ExternalGroupCredentialGenerator(config.getGroupConfiguration().getExternalServiceSecret(), Clock.systemUTC());

    AuthFilter<BasicCredentials, AuthenticatedAccount> accountAuthFilter = new BasicCredentialAuthFilter.Builder<AuthenticatedAccount>().setAuthenticator(accountAuthenticator).buildAuthFilter();
    AuthFilter<BasicCredentials, DisabledPermittedAuthenticatedAccount> disabledPermittedAccountAuthFilter = new BasicCredentialAuthFilter.Builder<DisabledPermittedAuthenticatedAccount>().setAuthenticator(disabledPermittedAccountAuthenticator).buildAuthFilter();
    AuthFilter<BasicCredentials, GroupUser> groupUserAuthFilter = new BasicCredentialAuthFilter.Builder<GroupUser>().setAuthenticator(groupUserAuthenticator).buildAuthFilter();

    environment.servlets().addFilter("RemoteDeprecationFilter", new RemoteDeprecationFilter(dynamicConfig.getRemoteDeprecationConfiguration()))
        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");

    environment.jersey().register(ProtocolBufferMessageBodyProvider.class);
    environment.jersey().register(ProtocolBufferValidationErrorMessageBodyWriter.class);
    environment.jersey().register(InvalidProtocolBufferExceptionMapper.class);
    // environment.jersey().register(CompletionExceptionMapper.class);

    environment.jersey().register(new ContentLengthFilter(TrafficSource.HTTP));
    environment.jersey().register(MultiRecipientMessageProvider.class);

    environment.jersey().register(new MetricsApplicationEventListener(TrafficSource.HTTP));
    environment.jersey().register(new PolymorphicAuthDynamicFeature<>(ImmutableMap.of(AuthenticatedAccount.class, accountAuthFilter, DisabledPermittedAuthenticatedAccount.class, disabledPermittedAccountAuthFilter, GroupUser.class, groupUserAuthFilter)));
    environment.jersey().register(new WebsocketRefreshApplicationEventListener(clientPresenceManager));
    environment.jersey().register(new TimestampResponseFilter());

    environment.jersey().register(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(AuthenticatedAccount.class, DisabledPermittedAuthenticatedAccount.class, GroupUser.class)));

    environment.jersey().register(new GroupsController(groupsManager, zkSecretParams, profileCdnPolicySigner, profileCdnPolicyGenerator, config.getGroupConfiguration(), externalGroupCredentialGenerator));

    ///
    WebSocketEnvironment<AuthenticatedAccount> webSocketEnvironment = new WebSocketEnvironment<>(environment, config.getWebSocketConfiguration(), 90000);
    webSocketEnvironment.setAuthenticator(new WebSocketAccountAuthenticator(accountAuthenticator));
    webSocketEnvironment.setConnectListener(new AuthenticatedConnectListener(receiptSender, messagesManager, messageSender, null, clientPresenceManager, retrySchedulingExecutor));
    webSocketEnvironment.jersey().register(new WebsocketRefreshApplicationEventListener(clientPresenceManager));
    webSocketEnvironment.jersey().register(new ContentLengthFilter(TrafficSource.WEBSOCKET));
    webSocketEnvironment.jersey().register(MultiRecipientMessageProvider.class);
    webSocketEnvironment.jersey().register(new MetricsApplicationEventListener(TrafficSource.WEBSOCKET));
    webSocketEnvironment.jersey().register(new KeepAliveController(clientPresenceManager));

    // these should be common, but use @Auth DisabledPermittedAccount, which isn’t
    // supported yet on websocket
    environment.jersey().register(new AccountController(pendingAccountsManager, accountsManager, usernamesManager, abusiveHostRules, rateLimiters, turnTokenGenerator, config.getTestDevices(), transitionalRecaptchaClient, gcmSender
    // , apnSender
        , localParams, config.getServiceConfiguration()));
    environment.jersey().register(new KeysController(rateLimiters, keysScyllaDb, accountsManager, preKeyRateLimiter, rateLimitChallengeManager));

    final List<Object> commonControllers = List.of(
        new AttachmentControllerV1(rateLimiters, minioConfig.getAccessKey(), minioConfig.getAccessSecret(), minioConfig.getAttachmentBucket(), minioConfig.getUri()),
        new AttachmentControllerV2(rateLimiters, minioConfig.getAccessKey(), minioConfig.getAccessSecret(), minioConfig.getRegion(), minioConfig.getAttachmentBucket()),
        new CertificateController(new CertificateGenerator(config.getDeliveryCertificate().getCertificate(), config.getDeliveryCertificate().getPrivateKey(), config.getDeliveryCertificate().getExpiresDays()), zkAuthOperations),
        new ChallengeController(rateLimitChallengeManager),
        new DeviceController(pendingDevicesManager, accountsManager, messagesManager, keysScyllaDb, rateLimiters, config.getMaxDevices(), localParams.getVerificationCodeLifetime()),
        new PlainDirectoryController(rateLimiters, accountsManager),
        new MessageController(rateLimiters, messageSender, receiptSender, accountsManager, messagesManager, unsealedSenderRateLimiter, null, dynamicConfig, rateLimitChallengeManager, reportMessageManager, metricsCluster, declinedMessageReceiptExecutor, multiRecipientMessageExecutor),
        new PaymentsController(currencyManager, paymentsCredentialsGenerator),
        new ProfileController(clock, rateLimiters, accountsManager, profilesManager, usernamesManager, profileBadgeConverter, config.getBadges(), minioClient, profileCdnPolicyGenerator, profileCdnPolicySigner, minioConfig.getProfileBucket(), zkProfileOperations),
        new ProvisioningController(rateLimiters, provisioningManager),
        // new RemoteConfigController(remoteConfigsManager,
        // config.getRemoteConfigConfiguration().getAuthorizedTokens(),
        // config.getRemoteConfigConfiguration().getGlobalConfig()),
        new SecureStorageController(storageCredentialsGenerator),
        new StickerController(rateLimiters, minioConfig.getAccessKey(), minioConfig.getAccessSecret(), minioConfig.getRegion(), minioConfig.getProfileBucket()),
        new DebugLogController(rateLimiters, minioConfig.getAccessKey(), minioConfig.getAccessSecret(), minioConfig.getRegion(), minioConfig.getDebuglogBucket()));
    for (Object controller : commonControllers) {
      environment.jersey().register(controller);
      webSocketEnvironment.jersey().register(controller);
    }

    WebSocketEnvironment<AuthenticatedAccount> provisioningEnvironment = new WebSocketEnvironment<>(environment, webSocketEnvironment.getRequestLog(), 60000);
    provisioningEnvironment.jersey().register(new WebsocketRefreshApplicationEventListener(clientPresenceManager));
    provisioningEnvironment.setConnectListener(new ProvisioningConnectListener(pubSubManager));
    provisioningEnvironment.jersey().register(new MetricsApplicationEventListener(TrafficSource.WEBSOCKET));
    provisioningEnvironment.jersey().register(new KeepAliveController(clientPresenceManager));

    registerCorsFilter(environment);
    registerExceptionMappers(environment, webSocketEnvironment, provisioningEnvironment);

    RateLimitChallengeExceptionMapper rateLimitChallengeExceptionMapper = new RateLimitChallengeExceptionMapper(rateLimitChallengeManager);

    environment.jersey().register(rateLimitChallengeExceptionMapper);
    webSocketEnvironment.jersey().register(rateLimitChallengeExceptionMapper);
    provisioningEnvironment.jersey().register(rateLimitChallengeExceptionMapper);

    WebSocketResourceProviderFactory<AuthenticatedAccount> webSocketServlet = new WebSocketResourceProviderFactory<>(webSocketEnvironment, AuthenticatedAccount.class, config.getWebSocketConfiguration());
    WebSocketResourceProviderFactory<AuthenticatedAccount> provisioningServlet = new WebSocketResourceProviderFactory<>(provisioningEnvironment, AuthenticatedAccount.class, config.getWebSocketConfiguration());

    ServletRegistration.Dynamic websocket = environment.servlets().addServlet("WebSocket", webSocketServlet);
    ServletRegistration.Dynamic provisioning = environment.servlets().addServlet("Provisioning", provisioningServlet);

    websocket.addMapping("/v1/websocket/");
    websocket.setAsyncSupported(true);

    provisioning.addMapping("/v1/websocket/provisioning/");
    provisioning.setAsyncSupported(true);

    environment.admin().addTask(new SetRequestLoggingEnabledTask());
    environment.admin().addTask(new SetCrawlerAccelerationTask(accountDatabaseCrawlerCache));

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

  private void registerExceptionMappers(Environment environment, WebSocketEnvironment<AuthenticatedAccount> webSocketEnvironment, WebSocketEnvironment<AuthenticatedAccount> provisioningEnvironment) {
    environment.jersey().register(new LoggingUnhandledExceptionMapper());
    environment.jersey().register(new IOExceptionMapper());
    environment.jersey().register(new RateLimitExceededExceptionMapper());
    environment.jersey().register(new InvalidWebsocketAddressExceptionMapper());
    environment.jersey().register(new DeviceLimitExceededExceptionMapper());
    environment.jersey().register(new RetryLaterExceptionMapper());
    environment.jersey().register(new ServerRejectedExceptionMapper());

    webSocketEnvironment.jersey().register(new LoggingUnhandledExceptionMapper());
    webSocketEnvironment.jersey().register(new IOExceptionMapper());
    webSocketEnvironment.jersey().register(new RateLimitExceededExceptionMapper());
    webSocketEnvironment.jersey().register(new InvalidWebsocketAddressExceptionMapper());
    webSocketEnvironment.jersey().register(new DeviceLimitExceededExceptionMapper());
    webSocketEnvironment.jersey().register(new RetryLaterExceptionMapper());
    webSocketEnvironment.jersey().register(new ServerRejectedExceptionMapper());

    provisioningEnvironment.jersey().register(new LoggingUnhandledExceptionMapper());
    provisioningEnvironment.jersey().register(new IOExceptionMapper());
    provisioningEnvironment.jersey().register(new RateLimitExceededExceptionMapper());
    provisioningEnvironment.jersey().register(new InvalidWebsocketAddressExceptionMapper());
    provisioningEnvironment.jersey().register(new DeviceLimitExceededExceptionMapper());
    provisioningEnvironment.jersey().register(new RetryLaterExceptionMapper());
    provisioningEnvironment.jersey().register(new ServerRejectedExceptionMapper());
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
