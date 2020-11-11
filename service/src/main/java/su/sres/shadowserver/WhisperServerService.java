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
import su.sres.websocket.WebSocketResourceProviderFactory;
import su.sres.websocket.setup.WebSocketEnvironment;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletRegistration;
import java.security.Security;
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

// excluded federation, reserved for future purposes
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.FederatedPeer;

import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.liquibase.NameableMigrationsBundle;
import su.sres.shadowserver.mappers.DeviceLimitExceededExceptionMapper;
import su.sres.shadowserver.mappers.IOExceptionMapper;
import su.sres.shadowserver.mappers.InvalidWebsocketAddressExceptionMapper;
import su.sres.shadowserver.mappers.RateLimitExceededExceptionMapper;
import su.sres.shadowserver.metrics.CpuUsageGauge;
import su.sres.shadowserver.metrics.FileDescriptorGauge;
import su.sres.shadowserver.metrics.FreeMemoryGauge;
import su.sres.shadowserver.metrics.NetworkReceivedGauge;
import su.sres.shadowserver.metrics.NetworkSentGauge;
import su.sres.shadowserver.providers.RedisClientFactory;
import su.sres.shadowserver.providers.RedisHealthCheck;
// import su.sres.shadowserver.push.APNSender;
// import su.sres.shadowserver.push.ApnFallbackManager;
import su.sres.shadowserver.push.GCMSender;
import su.sres.shadowserver.push.PushSender;
import su.sres.shadowserver.push.ReceiptSender;
import su.sres.shadowserver.push.WebsocketSender;
import su.sres.shadowserver.recaptcha.RecaptchaClient;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
import su.sres.shadowserver.s3.PolicySigner;
import su.sres.shadowserver.s3.PostPolicyGenerator;
import su.sres.shadowserver.storage.*;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.websocket.AuthenticatedConnectListener;
import su.sres.shadowserver.websocket.DeadLetterHandler;
import su.sres.shadowserver.websocket.ProvisioningConnectListener;
import su.sres.shadowserver.websocket.WebSocketAccountAuthenticator;
import su.sres.shadowserver.workers.CertificateCommand;
import su.sres.shadowserver.workers.CreatePendingAccountCommand;
import su.sres.shadowserver.workers.CertHashCommand;
import su.sres.shadowserver.workers.DeleteUserCommand;
import su.sres.shadowserver.workers.DirectoryCommand;
import su.sres.shadowserver.workers.VacuumCommand;
import su.sres.shadowserver.workers.ZkParamsCommand;

import java.util.Optional;

import io.dropwizard.auth.AuthFilter;
import io.dropwizard.auth.PolymorphicAuthDynamicFeature;
import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.auth.basic.BasicCredentialAuthFilter;
import io.dropwizard.auth.basic.BasicCredentials;

public class WhisperServerService extends Application<WhisperServerConfiguration> {

  static {
    Security.addProvider(new BouncyCastleProvider());
  }

  @Override
  public void initialize(Bootstrap<WhisperServerConfiguration> bootstrap) {
	  
    bootstrap.addCommand(new DirectoryCommand());
    bootstrap.addCommand(new VacuumCommand());
    bootstrap.addCommand(new CreatePendingAccountCommand());
    bootstrap.addCommand(new CertHashCommand());
    bootstrap.addCommand(new DeleteUserCommand());
    bootstrap.addCommand(new CertificateCommand());
    bootstrap.addCommand(new ZkParamsCommand());
    
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
    return "whisper-server";
  }

  @Override
  public void run(WhisperServerConfiguration config, Environment environment)
      throws Exception
  {
    SharedMetricRegistries.add(Constants.METRICS_NAME, environment.metrics());
    environment.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    environment.getObjectMapper().setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
    environment.getObjectMapper().setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
      
    JdbiFactory jdbiFactory = new JdbiFactory(DefaultNameStrategy.CHECK_EMPTY);
    Jdbi        accountJdbi = jdbiFactory.build(environment, config.getAccountsDatabaseConfiguration(), "accountdb");    
    Jdbi        messageJdbi = jdbiFactory.build(environment, config.getMessageStoreConfiguration(), "messagedb" );
    Jdbi        abuseJdbi   = jdbiFactory.build(environment, config.getAbuseDatabaseConfiguration(), "abusedb"  );
   
    FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("accounts_database", accountJdbi, config.getAccountsDatabaseConfiguration().getCircuitBreakerConfiguration());
    FaultTolerantDatabase messageDatabase = new FaultTolerantDatabase("message_database", messageJdbi, config.getMessageStoreConfiguration().getCircuitBreakerConfiguration());
    FaultTolerantDatabase abuseDatabase   = new FaultTolerantDatabase("abuse_database", abuseJdbi, config.getAbuseDatabaseConfiguration().getCircuitBreakerConfiguration());

    Accounts          accounts          = new Accounts(accountDatabase);
    PendingAccounts   pendingAccounts   = new PendingAccounts(accountDatabase);
    PendingDevices    pendingDevices    = new PendingDevices (accountDatabase);
    Usernames         usernames         = new Usernames(accountDatabase);
    ReservedUsernames reservedUsernames = new ReservedUsernames(accountDatabase);
    Profiles          profiles          = new Profiles(accountDatabase);
    Keys              keys              = new Keys(accountDatabase);
    Messages          messages          = new Messages(messageDatabase);
    AbusiveHostRules  abusiveHostRules  = new AbusiveHostRules(abuseDatabase);
    RemoteConfigs     remoteConfigs     = new RemoteConfigs(accountDatabase);

    RedisClientFactory cacheClientFactory         = new RedisClientFactory("main_cache", config.getCacheConfiguration().getUrl(), config.getCacheConfiguration().getReplicaUrls(), config.getCacheConfiguration().getCircuitBreakerConfiguration());
    RedisClientFactory pubSubClientFactory        = new RedisClientFactory("pubsub_cache", config.getPubsubCacheConfiguration().getUrl(), config.getPubsubCacheConfiguration().getReplicaUrls(), config.getPubsubCacheConfiguration().getCircuitBreakerConfiguration());
    RedisClientFactory directoryClientFactory     = new RedisClientFactory("directory_cache", config.getDirectoryConfiguration().getUrl(), config.getDirectoryConfiguration().getReplicaUrls(), config.getDirectoryConfiguration().getCircuitBreakerConfiguration());
    RedisClientFactory messagesClientFactory      = new RedisClientFactory("message_cache", config.getMessageCacheConfiguration().getRedisConfiguration().getUrl(), config.getMessageCacheConfiguration().getRedisConfiguration().getReplicaUrls(), config.getMessageCacheConfiguration().getRedisConfiguration().getCircuitBreakerConfiguration());
    RedisClientFactory pushSchedulerClientFactory = new RedisClientFactory("push_scheduler_cache", config.getPushScheduler().getUrl(), config.getPushScheduler().getReplicaUrls(), config.getPushScheduler().getCircuitBreakerConfiguration());
    
    ReplicatedJedisPool cacheClient         = cacheClientFactory.getRedisClientPool();
    ReplicatedJedisPool pubsubClient        = pubSubClientFactory.getRedisClientPool();
    ReplicatedJedisPool directoryClient     = directoryClientFactory.getRedisClientPool();
    ReplicatedJedisPool messagesClient      = messagesClientFactory.getRedisClientPool();
    ReplicatedJedisPool pushSchedulerClient = pushSchedulerClientFactory.getRedisClientPool();

    DirectoryManager           directory                  = new DirectoryManager(directoryClient);
    PendingAccountsManager     pendingAccountsManager     = new PendingAccountsManager(pendingAccounts, cacheClient);
    PendingDevicesManager      pendingDevicesManager      = new PendingDevicesManager (pendingDevices, cacheClient );
    AccountsManager            accountsManager            = new AccountsManager(accounts, directory, cacheClient);
    UsernamesManager           usernamesManager           = new UsernamesManager(usernames, reservedUsernames, cacheClient);
    // excluded federation, reserved for future purposes
    // FederatedClientManager     federatedClientManager     = new FederatedClientManager(environment, config.getJerseyClientConfiguration(), config.getFederationConfiguration());
    ProfilesManager            profilesManager            = new ProfilesManager(profiles, cacheClient);
    MessagesCache              messagesCache              = new MessagesCache(messagesClient, messages, accountsManager, config.getMessageCacheConfiguration().getPersistDelayMinutes());
    MessagesManager            messagesManager            = new MessagesManager(messages, messagesCache);
    RemoteConfigsManager       remoteConfigsManager       = new RemoteConfigsManager(remoteConfigs);
    DeadLetterHandler          deadLetterHandler          = new DeadLetterHandler(messagesManager);
    DispatchManager            dispatchManager            = new DispatchManager(pubSubClientFactory, Optional.of(deadLetterHandler));
    PubSubManager              pubSubManager              = new PubSubManager(pubsubClient, dispatchManager);
//    APNSender                  apnSender                  = new APNSender(accountsManager, config.getApnConfiguration());
    GCMSender                  gcmSender                  = new GCMSender(accountsManager, config.getGcmConfiguration().getApiKey());
    WebsocketSender            websocketSender            = new WebsocketSender(messagesManager, pubSubManager);
    
// excluded federation, reserved for future purposes
    //    FederatedPeerAuthenticator federatedPeerAuthenticator = new FederatedPeerAuthenticator(config.getFederationConfiguration());
    
    RateLimiters               rateLimiters               = new RateLimiters(config.getLimitsConfiguration(), cacheClient);
    
    AccountAuthenticator                  accountAuthenticator                  = new AccountAuthenticator(accountsManager);
    DisabledPermittedAccountAuthenticator disabledPermittedAccountAuthenticator = new DisabledPermittedAccountAuthenticator(accountsManager);
    
    ExternalServiceCredentialGenerator storageCredentialsGenerator = new ExternalServiceCredentialGenerator(config.getSecureStorageServiceConfiguration().getUserAuthenticationTokenSharedSecret(), new byte[0], false);    

//    ApnFallbackManager       apnFallbackManager  = new ApnFallbackManager(pushSchedulerClient, apnSender, accountsManager);
    
    PushSender               pushSender          = new PushSender(null, gcmSender, null, websocketSender, config.getPushConfiguration().getQueueSize());
// excluded federation, reserved for future purposes
    //    ReceiptSender            receiptSender       = new ReceiptSender(accountsManager, pushSender, federatedClientManager);
    ReceiptSender            receiptSender       = new ReceiptSender(accountsManager, pushSender);
    TurnTokenGenerator       turnTokenGenerator  = new TurnTokenGenerator(config.getTurnConfiguration());    
    RecaptchaClient          recaptchaClient     = new RecaptchaClient(config.getRecaptchaConfiguration().getSecret());
    
    ActiveUserCounter                    activeUserCounter               = new ActiveUserCounter(config.getMetricsFactory(), cacheClient);
    AccountCleaner                       accountCleaner                  = new AccountCleaner(accountsManager);
    PushFeedbackProcessor                pushFeedbackProcessor           = new PushFeedbackProcessor(accountsManager);
    List<AccountDatabaseCrawlerListener> accountDatabaseCrawlerListeners = List.of(pushFeedbackProcessor, activeUserCounter, accountCleaner);

    AccountDatabaseCrawlerCache accountDatabaseCrawlerCache = new AccountDatabaseCrawlerCache(cacheClient);
    AccountDatabaseCrawler      accountDatabaseCrawler      = new AccountDatabaseCrawler(accountsManager, accountDatabaseCrawlerCache, accountDatabaseCrawlerListeners, config.getAccountDatabaseCrawlerConfiguration().getChunkSize(), config.getAccountDatabaseCrawlerConfiguration().getChunkIntervalMs());

    messagesCache.setPubSubManager(pubSubManager, pushSender);

    //apnSender.setApnFallbackManager(apnFallbackManager);
    //environment.lifecycle().manage(apnFallbackManager);
    environment.lifecycle().manage(pubSubManager);
    environment.lifecycle().manage(pushSender);
    environment.lifecycle().manage(messagesCache);
    environment.lifecycle().manage(accountDatabaseCrawler);
    environment.lifecycle().manage(remoteConfigsManager);
    
    MinioClient            minioClient = new MinioClient(config.getCdnConfiguration().getUri(), config.getCdnConfiguration().getAccessKey(), config.getCdnConfiguration().getAccessSecret());
    PostPolicyGenerator    profileCdnPolicyGenerator  = new PostPolicyGenerator(config.getCdnConfiguration().getRegion(), config.getCdnConfiguration().getBucket(), config.getCdnConfiguration().getAccessKey());
    PolicySigner           profileCdnPolicySigner     = new PolicySigner(config.getCdnConfiguration().getAccessSecret(), config.getCdnConfiguration().getRegion());
    
    ServerSecretParams        zkSecretParams      = new ServerSecretParams(config.getZkConfig().getServerSecret());
    ServerZkProfileOperations zkProfileOperations = new ServerZkProfileOperations(zkSecretParams);
    ServerZkAuthOperations    zkAuthOperations    = new ServerZkAuthOperations(zkSecretParams);
    boolean                   isZkEnabled         = config.getZkConfig().isEnabled();
    
    /* excluded federation, reserved for future purposes
     *    
    AttachmentController attachmentController = new AttachmentController(rateLimiters, federatedClientManager, urlSigner);
    KeysController       keysController       = new KeysController(rateLimiters, keys, accountsManager, federatedClientManager);
    MessageController    messageController    = new MessageController(rateLimiters, pushSender, receiptSender, accountsManager, messagesManager, federatedClientManager, null);
    */
    AttachmentControllerV1 attachmentControllerV1 = new AttachmentControllerV1(rateLimiters, config.getAwsAttachmentsConfiguration().getAccessKey(), config.getAwsAttachmentsConfiguration().getAccessSecret(), config.getAwsAttachmentsConfiguration().getBucket()                                                  );
    AttachmentControllerV2 attachmentControllerV2 = new AttachmentControllerV2(rateLimiters, config.getAwsAttachmentsConfiguration().getAccessKey(), config.getAwsAttachmentsConfiguration().getAccessSecret(), config.getAwsAttachmentsConfiguration().getRegion(), config.getAwsAttachmentsConfiguration().getBucket());
    DebugLogController     debugLogController     = new DebugLogController    (rateLimiters, config.getDebugLogsConfiguration().getAccessKey(),    config.getDebugLogsConfiguration().getAccessSecret(),  config.getDebugLogsConfiguration().getRegion(),   config.getDebugLogsConfiguration().getBucket());
    KeysController         keysController         = new KeysController        (rateLimiters, keys, accountsManager);
    MessageController      messageController      = new MessageController     (rateLimiters, pushSender, receiptSender, accountsManager, messagesManager, null);
    ProfileController      profileController      = new ProfileController     (rateLimiters, accountsManager, profilesManager, usernamesManager, minioClient, profileCdnPolicyGenerator, profileCdnPolicySigner, config.getCdnConfiguration().getBucket(), zkProfileOperations, isZkEnabled);
    StickerController      stickerController      = new StickerController     (rateLimiters, config.getCdnConfiguration().getAccessKey(),         config.getCdnConfiguration().getAccessSecret(), config.getCdnConfiguration().getRegion(), config.getCdnConfiguration().getBucket());
    RemoteConfigController remoteConfigController = new RemoteConfigController(remoteConfigsManager, config.getRemoteConfigConfiguration().getAuthorizedTokens());

    /* excluded federation, reserved for future purposes
     
    environment.jersey().register(new AuthDynamicFeature(new BasicCredentialAuthFilter.Builder<Account>()
                                                             .setAuthenticator(deviceAuthenticator)
                                                                                                                                                                                   
                                                             .setPrincipal(Account.class)
                                                             .buildAuthFilter(),
                                                         new BasicCredentialAuthFilter.Builder<FederatedPeer>()
                                                             .setAuthenticator(federatedPeerAuthenticator)
                                                             .setPrincipal(FederatedPeer.class)                                                             
                                                             .buildAuthFilter()));
   */
  // excluded federation (?), reserved for future purposes
    //  environment.jersey().register(new AuthValueFactoryProvider.Binder());
 
    AuthFilter<BasicCredentials, Account>                  accountAuthFilter                  = new BasicCredentialAuthFilter.Builder<Account>().setAuthenticator(accountAuthenticator).buildAuthFilter                                  ();
    AuthFilter<BasicCredentials, DisabledPermittedAccount> disabledPermittedAccountAuthFilter = new BasicCredentialAuthFilter.Builder<DisabledPermittedAccount>().setAuthenticator(disabledPermittedAccountAuthenticator).buildAuthFilter();

    environment.jersey().register(new PolymorphicAuthDynamicFeature<>(ImmutableMap.of(Account.class, accountAuthFilter,
                                                                                      DisabledPermittedAccount.class, disabledPermittedAccountAuthFilter)));
    environment.jersey().register(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(Account.class, DisabledPermittedAccount.class)));
   
    environment.jersey().register(new AccountController(pendingAccountsManager, accountsManager, usernamesManager, abusiveHostRules, rateLimiters, messagesManager, turnTokenGenerator, config.getTestDevices(), recaptchaClient, gcmSender
    		// , apnSender
    		, config.getLocalParametersConfiguration(), config.getServiceConfiguration()));
    environment.jersey().register(new DeviceController(pendingDevicesManager, accountsManager, messagesManager, rateLimiters, config.getMaxDevices(), config.getLocalParametersConfiguration().getVerificationCodeLifetime()));
    environment.jersey().register(new PlainDirectoryController(rateLimiters, accountsManager));
    
 // excluded federation (?), reserved for future purposes
   // environment.jersey().register(new FederationControllerV1(accountsManager, attachmentController, messageController));
   // environment.jersey().register(new FederationControllerV2(accountsManager, attachmentController, messageController, keysController));
    
    environment.jersey().register(new ProvisioningController(rateLimiters, pushSender));
    environment.jersey().register(new CertificateController(new CertificateGenerator(config.getDeliveryCertificate().getCertificate(), config.getDeliveryCertificate().getPrivateKey(), config.getDeliveryCertificate().getExpiresDays()), zkAuthOperations, isZkEnabled));

    environment.jersey().register(new SecureStorageController(storageCredentialsGenerator));    
    environment.jersey().register(attachmentControllerV1);
    environment.jersey().register(attachmentControllerV2);
    environment.jersey().register(debugLogController);
    environment.jersey().register(keysController);
    environment.jersey().register(messageController);
    environment.jersey().register(profileController);
    environment.jersey().register(stickerController);
//    environment.jersey().register(remoteConfigController);

    ///
    WebSocketEnvironment<Account> webSocketEnvironment = new WebSocketEnvironment<>(environment, config.getWebSocketConfiguration(), 90000);
    webSocketEnvironment.setAuthenticator(new WebSocketAccountAuthenticator(accountAuthenticator));
    webSocketEnvironment.setConnectListener(new AuthenticatedConnectListener(pushSender, receiptSender, messagesManager, pubSubManager, null));
    webSocketEnvironment.jersey().register(new KeepAliveController(pubSubManager));
    webSocketEnvironment.jersey().register(messageController);
    webSocketEnvironment.jersey().register(profileController);
    webSocketEnvironment.jersey().register(attachmentControllerV1);
    webSocketEnvironment.jersey().register(attachmentControllerV2);
    webSocketEnvironment.jersey().register(remoteConfigController);

    WebSocketEnvironment<Account> provisioningEnvironment = new WebSocketEnvironment<>(environment, webSocketEnvironment.getRequestLog(), 60000);
    provisioningEnvironment.setConnectListener(new ProvisioningConnectListener(pubSubManager));
    provisioningEnvironment.jersey().register(new KeepAliveController(pubSubManager));

    registerCorsFilter(environment);
    registerExceptionMappers(environment, webSocketEnvironment, provisioningEnvironment);

    WebSocketResourceProviderFactory<Account> webSocketServlet    = new WebSocketResourceProviderFactory<>(webSocketEnvironment, Account.class);
    WebSocketResourceProviderFactory<Account> provisioningServlet = new WebSocketResourceProviderFactory<>(provisioningEnvironment, Account.class);

    ServletRegistration.Dynamic websocket    = environment.servlets().addServlet("WebSocket", webSocketServlet      );
    ServletRegistration.Dynamic provisioning = environment.servlets().addServlet("Provisioning", provisioningServlet);

    websocket.addMapping("/v1/websocket/");
    websocket.setAsyncSupported(true);

    provisioning.addMapping("/v1/websocket/provisioning/");
    provisioning.setAsyncSupported(true);
    
///

    environment.healthChecks().register("directory", new RedisHealthCheck(directoryClient));
    environment.healthChecks().register("cache", new RedisHealthCheck(cacheClient));  

    environment.metrics().register(name(CpuUsageGauge.class, "cpu"), new CpuUsageGauge());
    environment.metrics().register(name(FreeMemoryGauge.class, "free_memory"), new FreeMemoryGauge());
    environment.metrics().register(name(NetworkSentGauge.class, "bytes_sent"), new NetworkSentGauge());
    environment.metrics().register(name(NetworkReceivedGauge.class, "bytes_received"), new NetworkReceivedGauge());
    environment.metrics().register(name(FileDescriptorGauge.class, "fd_count"), new FileDescriptorGauge());
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
