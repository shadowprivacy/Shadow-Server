/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

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
import net.logstash.logback.argument.StructuredArguments;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import redis.clients.jedis.Jedis;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.storage.DirectoryManager.BatchOperationHandle;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.SystemMapper;

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

  private static final Timer redisSetTimer = metricRegistry.timer(name(AccountsManager.class, "redisSet"));
  private static final Timer redisUserLoginGetTimer = metricRegistry
      .timer(name(AccountsManager.class, "redisUserLoginGet"));
  private static final Timer redisUuidGetTimer = metricRegistry.timer(name(AccountsManager.class, "redisUuidGet"));
  private static final Timer redisDeleteTimer = metricRegistry.timer(name(AccountsManager.class, "redisDelete"));

  private static final String DELETE_COUNTER_NAME = name(AccountsManager.class, "deleteCounter");
  private static final String DELETE_ERROR_COUNTER_NAME = name(AccountsManager.class, "deleteError");
  private static final String DELETION_REASON_TAG_NAME = "reason";

  private static final String SCYLLA_MIGRATION_ERROR_COUNTER_NAME = name(AccountsManager.class, "migration", "error");
  private static final Counter SCYLLA_MIGRATION_COMPARISON_COUNTER = Metrics.counter(name(AccountsManager.class, "migration", "comparisons"));
  private static final String SCYLLA_MIGRATION_MISMATCH_COUNTER_NAME = name(AccountsManager.class, "migration", "mismatches");

  private final Logger logger = LoggerFactory.getLogger(AccountsManager.class);

  private final Accounts accounts;
  private final AccountsScyllaDb accountsScyllaDb;
  private final FaultTolerantRedisCluster cacheCluster;
  private final DirectoryManager directory;
  private final KeysScyllaDb keysScyllaDb;
  private final MessagesManager messagesManager;
  private final UsernamesManager usernamesManager;
  private final ProfilesManager profilesManager;
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

  public AccountsManager(Accounts accounts, AccountsScyllaDb accountsScyllaDb, DirectoryManager directory, FaultTolerantRedisCluster cacheCluster, final KeysScyllaDb keysScyllaDb, final MessagesManager messagesManager, final UsernamesManager usernamesManager, final ProfilesManager profilesManager) {
    this.accounts = accounts;
    this.accountsScyllaDb = accountsScyllaDb;
    this.directory = directory;
    this.cacheCluster = cacheCluster;
    this.keysScyllaDb = keysScyllaDb;
    this.messagesManager = messagesManager;
    this.usernamesManager = usernamesManager;
    this.profilesManager = profilesManager;
    this.mapper = SystemMapper.getMapper();
    this.migrationComparisonMapper = mapper.copy();
    migrationComparisonMapper.addMixIn(Account.class, AccountComparisonMixin.class);
    migrationComparisonMapper.addMixIn(Device.class, DeviceComparisonMixin.class);

    accountCreateLock = new AtomicInteger(0);
  }

  // this is used by directory restore and DirectoryUpdater
  public List<Account> getAll(int offset, int length) {
    return accounts.getAll(offset, length);
  }

  public boolean create(Account account) {

    accountCreateLock.getAndIncrement();
    setAccountCreationLock();

    long directoryVersion = getDirectoryVersion();
    long newDirectoryVersion = directoryVersion + 1L;

    try (Timer.Context ignored = createTimer.time()) {
      final UUID originalUuid = account.getUuid();

      boolean freshUser = databaseCreate(account, newDirectoryVersion);

      // databaseCreate() sometimes updates the UUID, if there was a userLogin
      // conflict.
      // for metrics, we want scylla to run with the same original UUID
      final UUID actualUuid = account.getUuid();

      try {
        if (scyllaWriteEnabled()) {
          account.setUuid(originalUuid);

          runSafelyAndRecordMetrics(() -> scyllaCreate(account, newDirectoryVersion), Optional.of(account.getUuid()), freshUser,
              (databaseResult, dynamoResult) -> {
                if (!account.getUuid().equals(actualUuid)) {
                  logger.warn("scyllaCreate() did not return correct UUID");
                }

                if (databaseResult.equals(dynamoResult)) {
                  return Optional.empty();
                }

                if (dynamoResult) {
                  return Optional.of("scyllaFreshUser");
                }

                return Optional.of("dbFreshUser");
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

      return freshUser;
    } finally {

      if (accountCreateLock.decrementAndGet() == 0) {

        releaseAccountCreationLock();
      }
    }
  }

  // TODO: if directory stores anything except usernames in future, we'll need to
  // make this more complicated and include a lock as well. Mind the calls in
  // AccountController!
  public void update(Account account) {

    try (Timer.Context ignored = updateTimer.time()) {
      account.setScyllaDbMigrationVersion(account.getScyllaDbMigrationVersion() + 1);
      redisSet(account);

      // isRemoval hardcoded to false for now, tbc in future
      databaseUpdate(account, false, -1L);

      if (scyllaWriteEnabled()) {
        runSafelyAndRecordMetrics(() -> {
          try {
            scyllaUpdate(account);
          } catch (final ConditionalCheckFailedException e) {
            // meaning we are trying to update an account missing in scylla, but it should
            // be present elsewhere, so do not update the directory version
            // normally this should not be the case
            scyllaCreate(account, getDirectoryVersion());
          }
          return true;
        }, Optional.of(account.getUuid()), true,
            (databaseSuccess, dynamoSuccess) -> Optional.empty(), // both values are always true
            "update");
      }

    }
  }

  public Optional<Account> get(AmbiguousIdentifier identifier) {
    if (identifier.hasUserLogin())
      return get(identifier.getUserLogin());
    else if (identifier.hasUuid())
      return get(identifier.getUuid());
    else
      throw new AssertionError();
  }

  public Optional<Account> get(String userLogin) {
    try (Timer.Context ignored = getByUserLoginTimer.time()) {
      Optional<Account> account = redisGet(userLogin);

      if (!account.isPresent()) {
        account = databaseGet(userLogin);
        account.ifPresent(value -> redisSet(value));

        if (scyllaReadEnabled()) {
          runSafelyAndRecordMetrics(() -> scyllaGet(userLogin), Optional.empty(), account, this::compareAccounts,
              "getByUserLogin");
        }
      }

      return account;
    }
  }

  /*
   * possibly related to federation, reserved for future use
   * 
   * 
   * public boolean isRelayListed(String number) { byte[] token =
   * Util.getContactToken(number); Optional<ClientContact> contact =
   * directory.get(token);
   * 
   * return contact.isPresent() && !Util.isEmpty(contact.get().getRelay()); }
   */

  public Optional<Account> get(UUID uuid) {
    try (Timer.Context ignored = getByUuidTimer.time()) {
      Optional<Account> account = redisGet(uuid);

      if (!account.isPresent()) {
        account = databaseGet(uuid);
        account.ifPresent(value -> redisSet(value));

        if (scyllaReadEnabled()) {
          runSafelyAndRecordMetrics(() -> scyllaGet(uuid), Optional.of(uuid), account, this::compareAccounts,
              "getByUuid");
        }
      }

      return account;
    }
  }

  public List<Account> getAllFrom(int length) {
    return accounts.getAllFrom(length);
  }

  public List<Account> getAllFrom(UUID uuid, int length) {
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
        keysScyllaDb.delete(account);
        messagesManager.clear(account.getUuid());
        redisDelete(account);
        databaseDelete(account, newDirectoryVersion);

        if (scyllaDeleteEnabled()) {
          try {
            scyllaDelete(account, newDirectoryVersion);
          } catch (final Exception e) {
            logger.error("Could not delete account {} from scylla", account.getUuid().toString());
            Metrics.counter(SCYLLA_MIGRATION_ERROR_COUNTER_NAME, "action", "delete").increment();
          }
        }

        Metrics.counter(DELETE_COUNTER_NAME, DELETION_REASON_TAG_NAME, deletionReason.tagValue).increment();

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

      Metrics.counter(DELETE_ERROR_COUNTER_NAME,
          DELETION_REASON_TAG_NAME, deletionReason.tagValue).increment();

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
      cacheCluster.useCluster(connection -> connection.sync().del(getAccountMapKey(account.getUserLogin()), getAccountEntityKey(account.getUuid())));
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

  private boolean scyllaDeleteEnabled() {
    return true;
  }

  private boolean scyllaReadEnabled() {
    return true;
  }

  private boolean scyllaWriteEnabled() {
    return scyllaDeleteEnabled()
        && true;
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  public Optional<String> compareAccounts(final Optional<Account> maybeDatabaseAccount, final Optional<Account> maybeScyllaAccount) {

    if (maybeDatabaseAccount.isEmpty() && maybeScyllaAccount.isEmpty()) {
      return Optional.empty();
    }

    if (maybeDatabaseAccount.isEmpty()) {
      return Optional.of("dbMissing");
    }

    if (maybeScyllaAccount.isEmpty()) {
      return Optional.of("scyllaMissing");
    }

    final Account databaseAccount = maybeDatabaseAccount.get();
    final Account scyllaAccount = maybeScyllaAccount.get();

    final int uuidCompare = databaseAccount.getUuid().compareTo(scyllaAccount.getUuid());

    if (uuidCompare != 0) {
      return Optional.of("uuid");
    }

    final int userLoginCompare = databaseAccount.getUserLogin().compareTo(scyllaAccount.getUserLogin());

    if (userLoginCompare != 0) {
      return Optional.of("userLogin");
    }
    
    if (!Objects.equals(databaseAccount.getIdentityKey(), scyllaAccount.getIdentityKey())) {
      return Optional.of("identityKey");
    }

    if (!Objects.equals(databaseAccount.getCurrentProfileVersion(), scyllaAccount.getCurrentProfileVersion())) {
      return Optional.of("currentProfileVersion");
    }

    if (!Objects.equals(databaseAccount.getProfileName(), scyllaAccount.getProfileName())) {
      return Optional.of("profileName");
    }

    if (!Objects.equals(databaseAccount.getAvatar(), scyllaAccount.getAvatar())) {
      return Optional.of("avatar");
    }

    if (!Objects.equals(databaseAccount.getUnidentifiedAccessKey(), scyllaAccount.getUnidentifiedAccessKey())) {
      if (databaseAccount.getUnidentifiedAccessKey().isPresent() && scyllaAccount.getUnidentifiedAccessKey().isPresent()) {

        if (Arrays.compare(databaseAccount.getUnidentifiedAccessKey().get(), scyllaAccount.getUnidentifiedAccessKey().get()) != 0) {
          return Optional.of("unidentifiedAccessKey");
        }

      } else {
        return Optional.of("unidentifiedAccessKey");
      }
    }

    if (!Objects.equals(databaseAccount.isUnrestrictedUnidentifiedAccess(), scyllaAccount.isUnrestrictedUnidentifiedAccess())) {
      return Optional.of("unrestrictedUnidentifiedAccess");
    }

    if (!Objects.equals(databaseAccount.isDiscoverableByUserLogin(), scyllaAccount.isDiscoverableByUserLogin())) {
      return Optional.of("discoverableByPhoneNumber");
    }

    try {
      
      if (databaseAccount.getMasterDevice().isPresent() && scyllaAccount.getMasterDevice().isPresent()) {
        if (!Objects.equals(databaseAccount.getMasterDevice().get().getSignedPreKey(), scyllaAccount.getMasterDevice().get().getSignedPreKey())) {
          return Optional.of("masterDeviceSignedPreKey");
        }
        
        if (!Objects.equals(databaseAccount.getMasterDevice().get().getPushTimestamp(), scyllaAccount.getMasterDevice().get().getPushTimestamp())) {
          return Optional.of("masterDevicePushTimestamp");
        }
      }
      
      if (!serializedEquals(databaseAccount.getDevices(), scyllaAccount.getDevices())) {
        return Optional.of("devices");
      }

      if (!serializedEquals(databaseAccount, scyllaAccount)) {
        return Optional.of("serialization");
      }

    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
    
    return Optional.empty();
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private <T> void runSafelyAndRecordMetrics(Callable<T> callable, Optional<UUID> maybeUuid, final T databaseResult, final BiFunction<T, T, Optional<String>> mismatchClassifier, final String action) {

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

      final T scyllaResult = callable.call();
      compare(databaseResult, scyllaResult, mismatchClassifier, action, maybeUuid);

    } catch (final Exception e) {
      logger.error("Error running " + action + " in Scylla", e);

      Metrics.counter(SCYLLA_MIGRATION_ERROR_COUNTER_NAME, "action", action).increment();
    }
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private <T> void compare(final T databaseResult, final T scyllaResult, final BiFunction<T, T, Optional<String>> mismatchClassifier, final String action, final Optional<UUID> maybeUUid) {
    SCYLLA_MIGRATION_COMPARISON_COUNTER.increment();

    mismatchClassifier.apply(databaseResult, scyllaResult)
    .ifPresent(mismatchType -> {
      final String mismatchDescription = action + ":" + mismatchType;
      Metrics.counter(SCYLLA_MIGRATION_MISMATCH_COUNTER_NAME,
          "mismatchType", mismatchDescription)
          .increment();

      if (maybeUUid.isPresent()
         // && dynamicConfiguration.getAccountsDynamoDbMigrationConfiguration().isLogMismatches()
         )
      {
        final String abbreviatedCallChain = getAbbreviatedCallChain(new RuntimeException().getStackTrace());

        logger.info("Mismatched account data: {}", StructuredArguments.entries(Map.of(
            "type", mismatchDescription,
            "uuid", maybeUUid.get(),
            "callChain", abbreviatedCallChain
        )));
      }
    });
  }
  
  private String getAbbreviatedCallChain(final StackTraceElement[] stackTrace) {
    return Arrays.stream(stackTrace)
        .filter(stackTraceElement -> stackTraceElement.getClassName().contains("su.sres"))
        .filter(stackTraceElement -> !(stackTraceElement.getClassName().endsWith("AccountsManager") && stackTraceElement.getMethodName().contains("compare")))
        .map(stackTraceElement -> StringUtils.substringAfterLast(stackTraceElement.getClassName(), ".") + ":" + stackTraceElement.getMethodName())
        .collect(Collectors.joining(" -> "));
  }

  private static abstract class AccountComparisonMixin extends Account {

    @JsonIgnore
    private int scyllaDbMigrationVersion;
  }
  
  private static abstract class DeviceComparisonMixin extends Device {

    @JsonIgnore
    private long lastSeen;

  }
  
  private boolean serializedEquals(final Object database, final Object scylla) throws JsonProcessingException {
    final byte[] databaseSerialized = migrationComparisonMapper.writeValueAsBytes(database);
    final byte[] scyllaSerialized = migrationComparisonMapper.writeValueAsBytes(scylla);
    final int serializeCompare = Arrays.compare(databaseSerialized, scyllaSerialized);

    return serializeCompare == 0;
  }
}
