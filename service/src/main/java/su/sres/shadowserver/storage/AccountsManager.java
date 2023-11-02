/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import redis.clients.jedis.Jedis;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.controllers.AccountController;
import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.storage.DirectoryManager.BatchOperationHandle;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.SystemMapper;
import su.sres.shadowserver.util.Util;

import static com.codahale.metrics.MetricRegistry.name;

import static su.sres.shadowserver.storage.DirectoryManager.DIRECTORY_VERSION;
import static su.sres.shadowserver.storage.DirectoryManager.DIRECTORY_PLAIN;

public class AccountsManager {

  private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private static final Timer createTimer = metricRegistry.timer(name(AccountsManager.class, "create"));
  private static final Timer updateTimer = metricRegistry.timer(name(AccountsManager.class, "update"));
  private static final Timer deleteTimer = metricRegistry.timer(name(AccountsManager.class, "delete"));
  private static final Timer getByUserLoginTimer = metricRegistry.timer(name(AccountsManager.class, "getByUserLogin"));
  private static final Timer getByUuidTimer = metricRegistry.timer(name(AccountsManager.class, "getByUuid"));

  // TODO Remove this meter when external dependencies have been resolved
  // Note that this is deliberately namespaced to `AccountController` for metric
  // continuity.
  private static final Meter newUserMeter = metricRegistry.meter(name(AccountController.class, "brand_new_user"));

  private static final Timer redisSetTimer = metricRegistry.timer(name(AccountsManager.class, "redisSet"));
  private static final Timer redisUserLoginGetTimer = metricRegistry
      .timer(name(AccountsManager.class, "redisUserLoginGet"));
  private static final Timer redisUuidGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisUuidGet"));
  private static final Timer redisDeleteTimer = metricRegistry.timer(name(AccountsManager.class, "redisDelete"));

  private static final String CREATE_COUNTER_NAME = name(AccountsManager.class, "createCounter");
  private static final String DELETE_COUNTER_NAME = name(AccountsManager.class, "deleteCounter");
  private static final String DELETION_REASON_TAG_NAME = "reason";
  
  private final Logger logger = LoggerFactory.getLogger(AccountsManager.class);

  private final Accounts accounts;
  private final FaultTolerantRedisCluster cacheCluster;
  private final DeletedAccounts deletedAccounts;
  private final DirectoryManager directory;
  private final KeysScyllaDb keysScyllaDb;
  private final MessagesManager messagesManager;  
  private final UsernamesManager usernamesManager;
  private final ProfilesManager profilesManager;
  private final StoredVerificationCodeManager pendingAccounts;
  private final ObjectMapper mapper;

  public enum DeletionReason {
    ADMIN_DELETED("admin"),
    EXPIRED("expired"),
    USER_REQUEST("userRequest");

    private final String tagValue;

    DeletionReason(final String tagValue) {
      this.tagValue = tagValue;
    }
  }

  private static final String ACCOUNT_CREATION_LOCK_KEY = "AccountCreationLock";
  private static final String ACCOUNT_REMOVAL_LOCK_KEY = "AccountRemovalLock";
  private static final String DIRECTORY_RESTORE_LOCK_KEY = "DirectoryRestoreLock";

  private static final int CHUNK_SIZE = 1000;

  private final AtomicInteger accountCreateLock;
  
  public AccountsManager(Accounts accounts, DirectoryManager directory, FaultTolerantRedisCluster cacheCluster, final DeletedAccounts deletedAccounts, final KeysScyllaDb keysScyllaDb, final MessagesManager messagesManager, final UsernamesManager usernamesManager, final ProfilesManager profilesManager,
      final StoredVerificationCodeManager pendingAccounts) {    
    this.accounts = accounts;
    this.directory = directory;
    this.cacheCluster = cacheCluster;
    this.deletedAccounts = deletedAccounts;
    this.keysScyllaDb = keysScyllaDb;
    this.messagesManager = messagesManager;    
    this.usernamesManager = usernamesManager;
    this.profilesManager = profilesManager;
    this.pendingAccounts = pendingAccounts;
    this.mapper = SystemMapper.getMapper();
      
    accountCreateLock = new AtomicInteger(0);
  }

  // this is used by directory restore and DirectoryUpdater
  public List<Account> getAll(ScanRequest.Builder builder) {
    return accounts.getAll(builder);
  }

  public Account create(final String userLogin,
      final String password,
      final String signalAgent,
      final AccountAttributes accountAttributes) {

    accountCreateLock.getAndIncrement();
    setAccountCreationLock();

    long directoryVersion = getDirectoryVersion();
    long newDirectoryVersion = directoryVersion + 1L;

    try (Timer.Context ignored = createTimer.time()) {
      final Account account = new Account();

      Device device = new Device();
      device.setId(Device.MASTER_ID);
      device.setAuthenticationCredentials(new AuthenticationCredentials(password));
      device.setFetchesMessages(accountAttributes.getFetchesMessages());
      device.setRegistrationId(accountAttributes.getRegistrationId());
      device.setName(accountAttributes.getName());
      device.setCapabilities(accountAttributes.getCapabilities());
      device.setCreated(System.currentTimeMillis());
      device.setLastSeen(Util.todayInMillis());
      device.setUserAgent(signalAgent);

      account.setUserLogin(userLogin);

      Optional<UUID> oUUID = deletedAccounts.findUuid(userLogin);

      // This one will treat the new account the an old one being restored.
      // Potentially dangerous from the perspective of impersonation attack!
      if (oUUID.isPresent()) {
        account.setUuid(oUUID.get());
        deletedAccounts.remove(userLogin);

      } else {
        account.setUuid(UUID.randomUUID());
      }

      account.addDevice(device);

      account.setUnidentifiedAccessKey(accountAttributes.getUnidentifiedAccessKey());
      account.setUnrestrictedUnidentifiedAccess(accountAttributes.isUnrestrictedUnidentifiedAccess());
      account.setDiscoverableByUserLogin(accountAttributes.isDiscoverableByUserLogin());

      final UUID originalUuid = account.getUuid();

      boolean freshUser = scyllaCreate(account, newDirectoryVersion);

      // create() sometimes updates the UUID, if there was a user login conflict.
      // for metrics, we want secondary to run with the same original UUID
      final UUID actualUuid = account.getUuid();     

      redisSet(account);

      // building historic directories
      directory.buildHistoricDirectories(directoryVersion);

      // incrementing the directory version in Redis
      directory.setDirectoryVersion(newDirectoryVersion);

      // writing the account into the plain directory
      directory.redisUpdatePlainDirectory(account);

      // building incremental updates
      directory.buildIncrementalUpdates(newDirectoryVersion);

      final Tags tags;

      if (freshUser) {
        tags = Tags.of("type", "new");
        newUserMeter.mark();
      } else {
        tags = Tags.of("type", "reregister");
      }

      Metrics.counter(CREATE_COUNTER_NAME, tags).increment();

      pendingAccounts.remove(userLogin);

      if (!originalUuid.equals(actualUuid)) {
        messagesManager.clear(actualUuid);
        keysScyllaDb.delete(actualUuid);
        profilesManager.deleteAll(actualUuid);
      }

      return account;
    } finally {

      if (accountCreateLock.decrementAndGet() == 0) {

        releaseAccountCreationLock();
      }
    }
  }

  // TODO: if directory stores anything except usernames in future, we'll need to
  // make this more complicated and include a lock as well. Mind the calls in
  // AccountController!
  public Account update(Account account, Consumer<Account> updater) {
    return update(account, a -> {
      updater.accept(a);
      // assume that all updaters passed to the public method actually modify the
      // account
      return true;
    });
  }

  /**
   * Specialized version of {@link #updateDevice(Account, long, Consumer)} that
   * minimizes potentially contentious and redundant updates of
   * {@code device.lastSeen}
   */
  public Account updateDeviceLastSeen(Account account, Device device, final long lastSeen) {

    return update(account, a -> {

      final Optional<Device> maybeDevice = a.getDevice(device.getId());

      return maybeDevice.map(d -> {
        if (d.getLastSeen() >= lastSeen) {
          return false;
        }

        d.setLastSeen(lastSeen);

        return true;

      }).orElse(false);
    });
  }

  /**
   * @param account account to update
   * @param updater must return {@code true} if the account was actually updated
   */
  private Account update(Account account, Function<Account, Boolean> updater) {

    final Account updatedAccount;

    try (Timer.Context ignored = updateTimer.time()) {

      redisDelete(account);

      final UUID uuid = account.getUuid();

      // isRemoval hardcoded to false for now, tbc in future
      updatedAccount = updateWithRetries(account, updater, this::scyllaUpdate, () -> scyllaGet(uuid).get());

      redisSet(updatedAccount);
    }

    return updatedAccount;
  }

  private Account updateWithRetries(Account account, Function<Account, Boolean> updater, Consumer<Account> persister,
      Supplier<Account> retriever) {

    if (!updater.apply(account)) {
      return account;
    }

    final int maxTries = 10;
    int tries = 0;

    while (tries < maxTries) {

      try {
        persister.accept(account);

        final Account updatedAccount;
        try {
          updatedAccount = mapper.readValue(mapper.writeValueAsBytes(account), Account.class);
          updatedAccount.setUuid(account.getUuid());
        } catch (final IOException e) {
          // this should really, truly, never happen
          throw new IllegalArgumentException(e);
        }

        account.markStale();

        return updatedAccount;
      } catch (final ContestedOptimisticLockException e) {
        tries++;
        account = retriever.get();
        if (!updater.apply(account)) {
          return account;
        }
      }

    }

    throw new OptimisticLockRetryLimitExceededException();
  }

  public Account updateDevice(Account account, long deviceId, Consumer<Device> deviceUpdater) {
    return update(account, a -> {
      a.getDevice(deviceId).ifPresent(deviceUpdater);
      // assume that all updaters passed to the public method actually modify the
      // device
      return true;
    });
  }

  public Optional<Account> get(String userLogin) {
    try (Timer.Context ignored = getByUserLoginTimer.time()) {
      Optional<Account> account = redisGet(userLogin);

      if (account.isEmpty()) {
        account = scyllaGet(userLogin);
        account.ifPresent(this::redisSet);
      }

      return account;
    }
  }

  public Optional<Account> get(UUID uuid) {
    try (Timer.Context ignored = getByUuidTimer.time()) {
      Optional<Account> account = redisGet(uuid);

      if (account.isEmpty()) {
        account = scyllaGet(uuid);
        account.ifPresent(this::redisSet);
      }

      return account;
    }
  }  

  public AccountCrawlChunk getAllFromScylla(int length) {    
    return accounts.getAllFromStart(length);
  }

  public AccountCrawlChunk getAllFromScylla(UUID uuid, int length) {    
    return accounts.getAllFrom(uuid, length);
  }

  public void delete(final HashSet<Account> accountsToDelete, final DeletionReason deletionReason) {

    long directoryVersion = getDirectoryVersion();
    long newDirectoryVersion = directoryVersion + 1L;

    setAccountRemovalLock();

    try (Timer.Context ignored = deleteTimer.time()) {

      for (Account account : accountsToDelete) {

        usernamesManager.delete(account.getUuid());
        profilesManager.deleteAll(account.getUuid());
        keysScyllaDb.delete(account.getUuid());
        messagesManager.clear(account.getUuid());
        redisDelete(account);
        scyllaDelete(account, newDirectoryVersion);

        Metrics.counter(DELETE_COUNTER_NAME, DELETION_REASON_TAG_NAME, deletionReason.tagValue).increment();

        deletedAccounts.put(account.getUuid(), account.getUserLogin());

      }

      // building historic directories
      directory.buildHistoricDirectories(directoryVersion);

      // incrementing directory version in Redis and deleting the account from the
      // plain directory
      directory.setDirectoryVersion(newDirectoryVersion);
      directory.redisRemoveFromPlainDirectory(accountsToDelete);

      // building incremental updates
      directory.buildIncrementalUpdates(newDirectoryVersion);

    } catch (final Exception e) {
      logger.warn("Failed to delete account(s)", e);

      throw e;

    } finally {

      releaseAccountRemovalLock();
    }
  }

  private String getAccountMapKey(String userLogin) {
    return "AccountMap::" + userLogin;
  }

  private String getAccountEntityKey(UUID uuid) {

    return "Account3::" + uuid.toString();
  }

  private void redisSet(Account account) {
    try (Timer.Context ignored = redisSetTimer.time()) {
      final String accountJson = mapper.writeValueAsString(account);

      cacheCluster.useCluster(connection -> {
        final RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        commands.set(getAccountMapKey(account.getUserLogin()), account.getUuid().toString());
        commands.set(getAccountEntityKey(account.getUuid()), accountJson);
      });

    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Optional<Account> redisGet(String userLogin) {
    try (Timer.Context ignored = redisUserLoginGetTimer.time()) {
      final String uuid = cacheCluster.withCluster(connection -> connection.sync().get(getAccountMapKey(userLogin)));

      if (uuid != null)
        return redisGet(UUID.fromString(uuid));
      else
        return Optional.empty();

    } catch (IllegalArgumentException e) {
      logger.warn("Deserialization error", e);
      return Optional.empty();
    } catch (RedisException e) {
      logger.warn("Redis failure", e);
      return Optional.empty();
    }
  }

  private Optional<Account> redisGet(UUID uuid) {
    try (Timer.Context ignored = redisUuidGetTimer.time()) {
      final String json = cacheCluster.withCluster(connection -> connection.sync().get(getAccountEntityKey(uuid)));
      if (json != null) {
        Account account = mapper.readValue(json, Account.class);
        account.setUuid(uuid);
        return Optional.of(account);
      }
      return Optional.empty();
    } catch (IOException e) {
      logger.warn("Deserialization error", e);
      return Optional.empty();
    } catch (RedisException e) {
      logger.warn("Redis failure", e);
      return Optional.empty();
    }
  }

  private void redisDelete(final Account account) {
    try (final Timer.Context ignored = redisDeleteTimer.time()) {
      cacheCluster.useCluster(connection -> connection.sync()
          .del(getAccountMapKey(account.getUserLogin()), getAccountEntityKey(account.getUuid())));
    }
  }  

  public long getDirectoryVersion() {
    Jedis jedis = directory.accessDirectoryCache().getWriteResource();

    @Nullable
    String currentVersion = jedis.get(DIRECTORY_VERSION);

    jedis.close();

    if (currentVersion == null || "nil".equals(currentVersion)) {

      try {

        long tmp = accounts.restoreDirectoryVersion();         

        // restoring the recovered version to redis
        directory.setDirectoryVersion(tmp);

        return tmp;

      } catch (IllegalStateException e) {
        logger.warn("IllegalStateException received from an Scylla query for directory version, assuming 0.");
        return 0;
      }

    } else {
      return Long.parseLong(currentVersion);
    }
  }

  public void restorePlainDirectory() {

    // consider for now that we shall restore the directory only if it's completely
    // missing
    if (isPlainDirectoryExisting())
      return;

    setDirectoryRestoreLock();   

    BatchOperationHandle batchOperation = directory.startBatchOperation();
    int contactsProcessed = 0;

    try {
      logger.info("Restoring plain directory from PostgreSQL...");
                  
      final ScanRequest.Builder accountsScanRequestBuilder = ScanRequest.builder();
      
        List<Account> accounts = getAll(accountsScanRequestBuilder);
        contactsProcessed = accounts.size();

        for (Account account : accounts) {
          directory.redisUpdatePlainDirectory(batchOperation, account.getUserLogin(), mapper.writeValueAsString(new PlainDirectoryEntryValue(account.getUuid())));          
        }        
      
    } catch (JsonProcessingException e) {
      logger.error("There were errors while restoring the local directory from Scylla!", e);
    } finally {
      directory.stopBatchOperation(batchOperation);
    }

    logger.info(String.format("Local directory restoration complete (%d contacts processed).", contactsProcessed));
    releaseDirectoryRestoreLock();
  }

  public DirectoryManager getDirectoryManager() {
    return directory;
  }

  public ObjectMapper getMapper() {
    return mapper;
  }

  private boolean isPlainDirectoryExisting() {
    try (Jedis jedis = directory.accessDirectoryCache().getWriteResource()) {

      return jedis.exists(DIRECTORY_PLAIN);
    }
  }

  public void setAccountCreationLock() {
    cacheCluster.useCluster(connection -> connection.sync().setex(ACCOUNT_CREATION_LOCK_KEY, 60, ""));
  }

  public void setAccountRemovalLock() {
    cacheCluster.useCluster(connection -> connection.sync().setex(ACCOUNT_REMOVAL_LOCK_KEY, 60, ""));
  }

  public void setDirectoryRestoreLock() {
    cacheCluster.useCluster(connection -> connection.sync().setex(DIRECTORY_RESTORE_LOCK_KEY, 60, ""));
  }

  public void releaseAccountCreationLock() {
    cacheCluster.useCluster(connection -> connection.sync().del(ACCOUNT_CREATION_LOCK_KEY));
  }

  public void releaseAccountRemovalLock() {
    cacheCluster.useCluster(connection -> connection.sync().del(ACCOUNT_REMOVAL_LOCK_KEY));
  }

  public void releaseDirectoryRestoreLock() {
    cacheCluster.useCluster(connection -> connection.sync().del(DIRECTORY_RESTORE_LOCK_KEY));
  }

  public boolean getAccountCreationLock() {

    Long exists = cacheCluster.withCluster(connection -> connection.sync().exists(ACCOUNT_CREATION_LOCK_KEY));

    return exists != 0 ? true : false;
  }

  public boolean getAccountRemovalLock() {

    Long exists = cacheCluster.withCluster(connection -> connection.sync().exists(ACCOUNT_REMOVAL_LOCK_KEY));

    return exists != 0 ? true : false;
  }

  public boolean getDirectoryRestoreLock() {

    Long exists = cacheCluster.withCluster(connection -> connection.sync().exists(DIRECTORY_RESTORE_LOCK_KEY));

    return exists != 0 ? true : false;

  }

  private Optional<Account> scyllaGet(String userLogin) {
    return accounts.get(userLogin);
  }

  private Optional<Account> scyllaGet(UUID uuid) {
    return accounts.get(uuid);
  }

  private boolean scyllaCreate(Account account, long directoryVersion) {
    return accounts.create(account, directoryVersion);
  }

  private void scyllaUpdate(Account account) {
    accounts.update(account);
  }

  private void scyllaDelete(final Account account, long directoryVersion) {
    accounts.delete(account.getUuid(), directoryVersion);
  }  
}
