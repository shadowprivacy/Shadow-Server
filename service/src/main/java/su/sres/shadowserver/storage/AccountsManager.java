/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.RedisException;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.micrometer.core.instrument.Metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import redis.clients.jedis.Jedis;

import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;
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
    private static final String DELETION_REASON_TAG_NAME = "reason";

    private final Logger logger = LoggerFactory.getLogger(AccountsManager.class);

    private final Accounts accounts;
    private final FaultTolerantRedisCluster cacheCluster;
    private final DirectoryManager directory;
    private final Keys keys;
    private final MessagesManager messagesManager;
    private final UsernamesManager usernamesManager;
    private final ProfilesManager profilesManager;
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

    public AccountsManager(Accounts accounts, DirectoryManager directory, FaultTolerantRedisCluster cacheCluster, final Keys keys, final MessagesManager messagesManager, final UsernamesManager usernamesManager, final ProfilesManager profilesManager) {
	this.accounts = accounts;
	this.directory = directory;
	this.cacheCluster = cacheCluster;
	this.keys = keys;
	this.messagesManager = messagesManager;
	this.usernamesManager = usernamesManager;
	this.profilesManager = profilesManager;
	this.mapper = SystemMapper.getMapper();

	accountCreateLock = new AtomicInteger(0);
    }

    // this is used by directory restore and DirectoryUpdater
    public List<Account> getAll(int offset, int length) {
	return accounts.getAll(offset, length);
    }

    public boolean create(Account account) {

	accountCreateLock.getAndIncrement();
	setAccountCreationLock();

	long newDirectoryVersion = getDirectoryVersion() + 1L;

	try (Timer.Context ignored = createTimer.time()) {
	    boolean freshUser = databaseCreate(account, newDirectoryVersion);
	    redisSet(account);
	    // updateDirectory(account);

	    // recording the update
	    directory.recordUpdateUpdate(account);

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
	    redisSet(account);

	    // isRemoval hardcoded to false for now, tbc in future
	    databaseUpdate(account, false, -1L);
	    // updateDirectory(account);
	}
    }

    /*
     * public void remove(HashSet<Account> accountsToRemove) {
     * 
     * setAccountRemovalLock();
     * 
     * long newDirectoryVersion = getDirectoryVersion() + 1L;
     * 
     * try (Timer.Context ignored = updateTimer.time()) {
     * 
     * for (Account account : accountsToRemove) {
     * 
     * redisSet(account); databaseUpdate(account, true, newDirectoryVersion); }
     * 
     * // recording the update directory.recordUpdateRemoval(accountsToRemove);
     * 
     * // incrementing directory version in Redis and deleting the account from the
     * // plain directory directory.setDirectoryVersion(newDirectoryVersion);
     * directory.redisRemoveFromPlainDirectory(accountsToRemove);
     * 
     * // building incremental updates
     * directory.buildIncrementalUpdates(newDirectoryVersion);
     * 
     * } finally {
     * 
     * releaseAccountRemovalLock(); } }
     */

    public Optional<Account> get(AmbiguousIdentifier identifier) {
	if (identifier.hasUserLogin())
	    return get(identifier.getUserLogin());
	else if (identifier.hasUuid())
	    return get(identifier.getUuid());
	else
	    throw new AssertionError();
    }

    public Optional<Account> get(String number) {
	try (Timer.Context ignored = getByUserLoginTimer.time()) {
	    Optional<Account> account = redisGet(number);

	    if (!account.isPresent()) {
		account = databaseGet(number);
		account.ifPresent(value -> redisSet(value));
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

	long newDirectoryVersion = getDirectoryVersion() + 1L;

	setAccountRemovalLock();

	try (Timer.Context ignored = deleteTimer.time()) {

	    for (Account account : accountsToDelete) {

		usernamesManager.delete(account.getUuid());
		profilesManager.deleteAll(account.getUuid());
		keys.delete(account.getUserLogin());
		messagesManager.clear(account.getUserLogin(), account.getUuid());
		redisDelete(account);
		databaseDelete(account, newDirectoryVersion);

		Metrics.counter(DELETE_COUNTER_NAME, DELETION_REASON_TAG_NAME, deletionReason.tagValue).increment();

	    }

	    // recording the update
	    directory.recordUpdateRemoval(accountsToDelete);

	    // incrementing directory version in Redis and deleting the account from the
	    // plain directory
	    directory.setDirectoryVersion(newDirectoryVersion);
	    directory.redisRemoveFromPlainDirectory(accountsToDelete);

	    // building incremental updates
	    directory.buildIncrementalUpdates(newDirectoryVersion);

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
}
