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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import net.logstash.logback.argument.StructuredArguments;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import redis.clients.jedis.Jedis;
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.configuration.dynamic.DynamicAccountsScyllaDbMigrationConfiguration;
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

  private static final String SCYLLA_MIGRATION_ERROR_COUNTER_NAME = name(AccountsManager.class, "migration", "error");
  private static final Counter SCYLLA_MIGRATION_COMPARISON_COUNTER = Metrics.counter(name(AccountsManager.class, "migration", "comparisons"));
  private static final String SCYLLA_MIGRATION_MISMATCH_COUNTER_NAME = name(AccountsManager.class, "migration", "mismatches");

  private final Logger logger = LoggerFactory.getLogger(AccountsManager.class);

  private final Accounts accounts;
  private final AccountsScyllaDb accountsScyllaDb;
  private final FaultTolerantRedisCluster cacheCluster;
  private final DeletedAccounts deletedAccounts;
  private final DirectoryManager directory;
  private final KeysScyllaDb keysScyllaDb;
  private final MessagesManager messagesManager;
  private final MigrationMismatchedAccounts mismatchedAccounts;
  private final UsernamesManager usernamesManager;
  private final ProfilesManager profilesManager;
  private final StoredVerificationCodeManager pendingAccounts;
  private final ObjectMapper mapper;

  private final ObjectMapper migrationComparisonMapper;

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

  private final DynamicAccountsScyllaDbMigrationConfiguration dynConfig = new DynamicAccountsScyllaDbMigrationConfiguration();

  public AccountsManager(Accounts accounts, AccountsScyllaDb accountsScyllaDb, DirectoryManager directory, FaultTolerantRedisCluster cacheCluster, final DeletedAccounts deletedAccounts, final KeysScyllaDb keysScyllaDb, final MessagesManager messagesManager, final MigrationMismatchedAccounts mismatchedAccounts, final UsernamesManager usernamesManager, final ProfilesManager profilesManager,
      final StoredVerificationCodeManager pendingAccounts) {
    this.accounts = accounts;
    this.accountsScyllaDb = accountsScyllaDb;
    this.directory = directory;
    this.cacheCluster = cacheCluster;
    this.deletedAccounts = deletedAccounts;
    this.keysScyllaDb = keysScyllaDb;
    this.messagesManager = messagesManager;
    this.mismatchedAccounts = mismatchedAccounts;
    this.usernamesManager = usernamesManager;
    this.profilesManager = profilesManager;
    this.pendingAccounts = pendingAccounts;
    this.mapper = SystemMapper.getMapper();
    this.migrationComparisonMapper = mapper.copy();

    migrationComparisonMapper.addMixIn(Device.class, DeviceComparisonMixin.class);

    accountCreateLock = new AtomicInteger(0);
  }

  // this is used by directory restore and DirectoryUpdater
  public List<Account> getAll(int offset, int length) {
    return accounts.getAll(offset, length);
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

      boolean freshUser = primaryCreate(account, newDirectoryVersion);

      // create() sometimes updates the UUID, if there was a user login conflict.
      // for metrics, we want secondary to run with the same original UUID
      final UUID actualUuid = account.getUuid();

      try {
        if (secondaryWriteEnabled()) {
          account.setUuid(originalUuid);

          runSafelyAndRecordMetrics(() -> secondaryCreate(account, newDirectoryVersion), Optional.of(account.getUuid()), freshUser,
              (primaryResult, secondaryResult) -> {
                if (primaryResult.equals(secondaryResult)) {
                  return Optional.empty();
                }

                if (secondaryResult) {
                  return Optional.of("secondaryFreshUser");
                }

                return Optional.of("primaryFreshUser");
              },
              "create");
        }
      } finally {
        account.setUuid(actualUuid);
      }

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
      updatedAccount = updateWithRetries(account, updater, this::primaryUpdate, () -> primaryGet(uuid).get());

      if (secondaryWriteEnabled()) {
        runSafelyAndRecordMetrics(() -> secondaryGet(uuid).map(secondaryAccount -> {
          try {
            return updateWithRetries(secondaryAccount, updater, this::secondaryUpdate, () -> secondaryGet(uuid).get());
          } catch (final OptimisticLockRetryLimitExceededException e) {
            if (!dynConfig.isScyllaPrimary()) {
              accountsScyllaDb.putUuidForMigrationRetry(uuid);
            }

            throw e;
          }
        }),
            Optional.of(uuid),
            Optional.of(updatedAccount),
            this::compareAccounts,
            "update");
      }

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

      if (!account.isPresent()) {
        account = primaryGet(userLogin);
        account.ifPresent(value -> redisSet(value));

        if (secondaryReadEnabled()) {
          runSafelyAndRecordMetrics(() -> secondaryGet(userLogin), Optional.empty(), account, this::compareAccounts,
              "getByUserLogin");
        }
      }

      return account;
    }
  }

  public Optional<Account> get(UUID uuid) {
    try (Timer.Context ignored = getByUuidTimer.time()) {
      Optional<Account> account = redisGet(uuid);

      if (!account.isPresent()) {
        account = primaryGet(uuid);
        account.ifPresent(value -> redisSet(value));

        if (secondaryReadEnabled()) {
          runSafelyAndRecordMetrics(() -> secondaryGet(uuid), Optional.of(uuid), account, this::compareAccounts,
              "getByUuid");
        }
      }

      return account;
    }
  }

  public AccountCrawlChunk getAllFrom(int length) {
    return accounts.getAllFrom(length);
  }

  public AccountCrawlChunk getAllFrom(UUID uuid, int length) {
    return accounts.getAllFrom(uuid, length);
  }

  public AccountCrawlChunk getAllFromScylla(int length) {
    final int maxPageSize = dynConfig.getScyllaCrawlerScanPageSize();
    return accountsScyllaDb.getAllFromStart(length, maxPageSize);
  }

  public AccountCrawlChunk getAllFromScylla(UUID uuid, int length) {
    final int maxPageSize = dynConfig.getScyllaCrawlerScanPageSize();
    return accountsScyllaDb.getAllFrom(uuid, length, maxPageSize);
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
        primaryDelete(account, newDirectoryVersion);

        if (secondaryDeleteEnabled()) {
          try {
            secondaryDelete(account, newDirectoryVersion);
          } catch (final Exception e) {
            logger.error("Could not delete account {} from secondary", account.getUuid().toString());
            Metrics.counter(SCYLLA_MIGRATION_ERROR_COUNTER_NAME, "action", "delete").increment();
          }
        }

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

  private Optional<Account> primaryGet(String userLogin) {
    return dynConfig.isScyllaPrimary()
        ?
        scyllaGet(userLogin) :
        databaseGet(userLogin);
  }

  private Optional<Account> secondaryGet(String userLogin) {
    return dynConfig.isScyllaPrimary()
        ?
        databaseGet(userLogin) :
        scyllaGet(userLogin);
  }

  private Optional<Account> primaryGet(UUID uuid) {
    return dynConfig.isScyllaPrimary()
        ?
        scyllaGet(uuid) :
        databaseGet(uuid);
  }

  private Optional<Account> secondaryGet(UUID uuid) {
    return dynConfig.isScyllaPrimary()
        ?
        databaseGet(uuid) :
        scyllaGet(uuid);
  }

  private boolean primaryCreate(Account account, long directoryVersion) {
    return dynConfig.isScyllaPrimary()
        ?
        scyllaCreate(account, directoryVersion) :
        databaseCreate(account, directoryVersion);
  }

  private boolean secondaryCreate(Account account, long directoryVersion) {
    return dynConfig.isScyllaPrimary()
        ?
        databaseCreate(account, directoryVersion) :
        scyllaCreate(account, directoryVersion);
  }

  private void primaryUpdate(Account account) {
    if (dynConfig.isScyllaPrimary()) {
      scyllaUpdate(account);
    } else {
      databaseUpdate(account);
    }
  }

  private void secondaryUpdate(Account account) {
    if (dynConfig.isScyllaPrimary()) {
      databaseUpdate(account);
    } else {
      scyllaUpdate(account);
    }
  }

  private void primaryDelete(Account account, long directoryVersion) {
    if (dynConfig.isScyllaPrimary()) {
      scyllaDelete(account, directoryVersion);
    } else {
      databaseDelete(account, directoryVersion);
    }
  }

  private void secondaryDelete(Account account, long directoryVersion) {
    if (dynConfig.isScyllaPrimary()) {
      databaseDelete(account, directoryVersion);
    } else {
      scyllaDelete(account, directoryVersion);
    }
  }

  private Optional<Account> databaseGet(String userLogin) {
    return accounts.get(userLogin);
  }

  private Optional<Account> databaseGet(UUID uuid) {
    return accounts.get(uuid);
  }

  private boolean databaseCreate(Account account, long directoryVersion) {
    return accounts.create(account, directoryVersion);
  }

  private void databaseUpdate(Account account) {
    accounts.update(account);
  }

  private void databaseUpdate(Account account, boolean isRemoval, long directoryVersion) {
    if (!isRemoval) {
      accounts.update(account);

    }

    // else {
    // accounts.update(account, isRemoval, directoryVersion);
    // }
  }

  private void databaseDelete(final Account account, long directoryVersion) {
    accounts.delete(account.getUuid(), directoryVersion);
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
        logger.warn("IllegalStateException received from an SQL query for directory version, assuming 0.");
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

    int contactsAdded = 0;

    BatchOperationHandle batchOperation = directory.startBatchOperation();

    try {
      logger.info("Restoring plain directory from PostgreSQL...");
      int offset = 0;

      for (;;) {
        List<Account> accounts = getAll(offset, CHUNK_SIZE);

        if (accounts == null || accounts.isEmpty())
          break;
        else
          offset += accounts.size();

        for (Account account : accounts) {

          directory.redisUpdatePlainDirectory(batchOperation, account.getUserLogin(), mapper.writeValueAsString(new PlainDirectoryEntryValue(account.getUuid())));
          contactsAdded++;
        }

        logger.info("Processed " + CHUNK_SIZE + " local accounts...");
      }
    } catch (JsonProcessingException e) {
      logger.error("There were errors while restoring the local directory from PostgreSQL!", e);
    } finally {
      directory.stopBatchOperation(batchOperation);
    }

    logger.info(String.format("Local directory restoration complete (%d contacts processed).", contactsAdded));
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
    return accountsScyllaDb.get(userLogin);
  }

  private Optional<Account> scyllaGet(UUID uuid) {
    return accountsScyllaDb.get(uuid);
  }

  private boolean scyllaCreate(Account account, long directoryVersion) {
    return accountsScyllaDb.create(account, directoryVersion);
  }

  private void scyllaUpdate(Account account) {
    accountsScyllaDb.update(account);
  }

  private void scyllaDelete(final Account account, long directoryVersion) {
    accountsScyllaDb.delete(account.getUuid(), directoryVersion);
  }

  private boolean secondaryDeleteEnabled() {
    return dynConfig.isDeleteEnabled();
  }

  private boolean secondaryReadEnabled() {
    return dynConfig.isReadEnabled();
  }

  private boolean secondaryWriteEnabled() {
    return secondaryDeleteEnabled()
        && dynConfig.isWriteEnabled();
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public Optional<String> compareAccounts(final Optional<Account> maybePrimaryAccount, final Optional<Account> maybeSecondaryAccount) {

    if (maybePrimaryAccount.isEmpty() && maybeSecondaryAccount.isEmpty()) {
      return Optional.empty();
    }

    if (maybePrimaryAccount.isEmpty()) {
      return Optional.of("primaryMissing");
    }

    if (maybeSecondaryAccount.isEmpty()) {
      return Optional.of("secondaryMissing");
    }

    final Account primaryAccount = maybePrimaryAccount.get();
    final Account secondaryAccount = maybeSecondaryAccount.get();

    final int uuidCompare = primaryAccount.getUuid().compareTo(secondaryAccount.getUuid());

    if (uuidCompare != 0) {
      return Optional.of("uuid");
    }

    final int userLoginCompare = primaryAccount.getUserLogin().compareTo(secondaryAccount.getUserLogin());

    if (userLoginCompare != 0) {
      return Optional.of("userLogin");
    }

    if (!Objects.equals(primaryAccount.getIdentityKey(), secondaryAccount.getIdentityKey())) {
      return Optional.of("identityKey");
    }

    if (!Objects.equals(primaryAccount.getCurrentProfileVersion(), secondaryAccount.getCurrentProfileVersion())) {
      return Optional.of("currentProfileVersion");
    }

    if (!Objects.equals(primaryAccount.getProfileName(), secondaryAccount.getProfileName())) {
      return Optional.of("profileName");
    }

    if (!Objects.equals(primaryAccount.getAvatar(), secondaryAccount.getAvatar())) {
      return Optional.of("avatar");
    }

    if (!Objects.equals(primaryAccount.getUnidentifiedAccessKey(), secondaryAccount.getUnidentifiedAccessKey())) {
      if (primaryAccount.getUnidentifiedAccessKey().isPresent() && secondaryAccount.getUnidentifiedAccessKey()
          .isPresent()) {

        if (Arrays.compare(primaryAccount.getUnidentifiedAccessKey().get(),
            secondaryAccount.getUnidentifiedAccessKey().get()) != 0) {
          return Optional.of("unidentifiedAccessKey");
        }

      } else {
        return Optional.of("unidentifiedAccessKey");
      }
    }

    if (!Objects.equals(primaryAccount.isUnrestrictedUnidentifiedAccess(),
        secondaryAccount.isUnrestrictedUnidentifiedAccess())) {
      return Optional.of("unrestrictedUnidentifiedAccess");
    }

    if (!Objects.equals(primaryAccount.isDiscoverableByUserLogin(), secondaryAccount.isDiscoverableByUserLogin())) {
      return Optional.of("discoverableByUserLogin");
    }

    if (primaryAccount.getMasterDevice().isPresent() && secondaryAccount.getMasterDevice().isPresent()) {
      if (!Objects.equals(primaryAccount.getMasterDevice().get().getSignedPreKey(),
          secondaryAccount.getMasterDevice().get().getSignedPreKey())) {
        return Optional.of("masterDeviceSignedPreKey");
      }
    }

    try {
      if (!serializedEquals(primaryAccount.getDevices(), secondaryAccount.getDevices())) {
        return Optional.of("devices");
      }

      if (primaryAccount.getVersion() != secondaryAccount.getVersion()) {
        return Optional.of("version");
      }

      if (primaryAccount.getMasterDevice().isPresent() && secondaryAccount.getMasterDevice().isPresent()) {
        if (Math.abs(primaryAccount.getMasterDevice().get().getPushTimestamp() -
            secondaryAccount.getMasterDevice().get().getPushTimestamp()) > 60 * 1_000L) {
          // These are generally few milliseconds off, because the setter uses
          // System.currentTimeMillis() internally,
          // but we can be more relaxed
          return Optional.of("masterDevicePushTimestamp");
        }
      }

      if (!serializedEquals(primaryAccount, secondaryAccount)) {
        return Optional.of("serialization");
      }

    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }

    return Optional.empty();
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private <T> void runSafelyAndRecordMetrics(Callable<T> callable, Optional<UUID> maybeUuid, final T primaryResult,
      final BiFunction<T, T, Optional<String>> mismatchClassifier, final String action) {

    if (maybeUuid.isPresent()) {
      // the only time we don’t have a UUID is in getByUserLogin, which is
      // sufficiently low volume to not be a concern, and
      // it will also be gated by the global readEnabled configuration
      final boolean enrolled = true;

      if (!enrolled) {
        return;
      }
    }

    try {

      final T secondaryResult = callable.call();
      compare(primaryResult, secondaryResult, mismatchClassifier, action, maybeUuid);

    } catch (final Exception e) {
      logger.error("Error running " + action + " in Scylla", e);

      Metrics.counter(SCYLLA_MIGRATION_ERROR_COUNTER_NAME, "action", action).increment();
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private <T> void compare(final T primaryResult, final T secondaryResult,
      final BiFunction<T, T, Optional<String>> mismatchClassifier, final String action,
      final Optional<UUID> maybeUUid) {
    SCYLLA_MIGRATION_COMPARISON_COUNTER.increment();

    mismatchClassifier.apply(primaryResult, secondaryResult)
        .ifPresent(mismatchType -> {
          final String mismatchDescription = action + ":" + mismatchType;
          Metrics.counter(SCYLLA_MIGRATION_MISMATCH_COUNTER_NAME,
              "mismatchType", mismatchDescription)
              .increment();

          maybeUUid.ifPresent(uuid -> {

            if (dynConfig.isPostCheckMismatches()) {
              mismatchedAccounts.put(uuid);
            }            
          });
        });
  }

  private String getAbbreviatedCallChain(final StackTraceElement[] stackTrace) {
    return Arrays.stream(stackTrace)
        .filter(stackTraceElement -> stackTraceElement.getClassName().contains("su.sres"))
        .filter(stackTraceElement -> !(stackTraceElement.getClassName().endsWith("AccountsManager") && stackTraceElement.getMethodName().contains("compare")))
        .map(stackTraceElement -> StringUtils.substringAfterLast(stackTraceElement.getClassName(), ".") + ":" + stackTraceElement.getMethodName())
        .collect(Collectors.joining(" -> "));
  }

  private static abstract class DeviceComparisonMixin extends Device {

    @JsonIgnore
    private long lastSeen;

    @JsonIgnore
    private long pushTimestamp;

  }

  private boolean serializedEquals(final Object primary, final Object secondary) throws JsonProcessingException {
    final byte[] primarySerialized = migrationComparisonMapper.writeValueAsBytes(primary);
    final byte[] secondarySerialized = migrationComparisonMapper.writeValueAsBytes(secondary);
    final int serializeCompare = Arrays.compare(primarySerialized, secondarySerialized);

    return serializeCompare == 0;
  }
}
