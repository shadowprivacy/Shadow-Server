/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.fasterxml.uuid.UUIDComparator;
import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit.EmbeddedPostgresRules;
import com.opentable.db.postgres.junit.PreparedDbRule;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.transaction.TransactionException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.storage.mappers.AccountRowMapper;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

public class AccountsTest {

    @Rule
    public PreparedDbRule db = EmbeddedPostgresRules.preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

    private Accounts accounts;

    @Before
    public void setupAccountsDao() {
	FaultTolerantDatabase faultTolerantDatabase = new FaultTolerantDatabase("accountsTest",
		Jdbi.create(db.getTestDatabase()),
		new CircuitBreakerConfiguration());

	this.accounts = new Accounts(faultTolerantDatabase);
    }

    @Ignore //
    @Test
    public void testStore() throws SQLException, IOException {
	Device device = generateDevice(1);
	Account account = generateAccount("johndoe", UUID.randomUUID(), Collections.singleton(device));

	long directoryVersion = 10;

	accounts.create(account, directoryVersion);

	PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM accounts WHERE number = ?");
	verifyStoredState(statement, "johndoe", account.getUuid(), account);
    }

    @Ignore //
    @Test
    public void testStoreMulti() throws SQLException, IOException {
	Set<Device> devices = new HashSet<>();
	devices.add(generateDevice(1));
	devices.add(generateDevice(2));

	Account account = generateAccount("johndoe", UUID.randomUUID(), devices);

	long directoryVersion = 10;

	accounts.create(account, directoryVersion);

	PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM accounts WHERE number = ?");
	verifyStoredState(statement, "johndoe", account.getUuid(), account);
    }

    @Ignore //
    @Test
    public void testRetrieve() {
	Set<Device> devicesFirst = new HashSet<>();
	devicesFirst.add(generateDevice(1));
	devicesFirst.add(generateDevice(2));

	UUID uuidFirst = UUID.randomUUID();
	Account accountFirst = generateAccount("johndoe", uuidFirst, devicesFirst);

	Set<Device> devicesSecond = new HashSet<>();
	devicesSecond.add(generateDevice(1));
	devicesSecond.add(generateDevice(2));

	UUID uuidSecond = UUID.randomUUID();
	Account accountSecond = generateAccount("+14152221111", uuidSecond, devicesSecond);

	long directoryVersionFirst = 10;
	long directoryVersionSecond = directoryVersionFirst + 1L;

	accounts.create(accountFirst, directoryVersionFirst);
	accounts.create(accountSecond, directoryVersionSecond);

	Optional<Account> retrievedFirst = accounts.get("johndoe");
	Optional<Account> retrievedSecond = accounts.get("+14152221111");

	assertThat(retrievedFirst.isPresent()).isTrue();
	assertThat(retrievedSecond.isPresent()).isTrue();

	verifyStoredState("johndoe", uuidFirst, retrievedFirst.get(), accountFirst);
	verifyStoredState("+14152221111", uuidSecond, retrievedSecond.get(), accountSecond);

	retrievedFirst = accounts.get(uuidFirst);
	retrievedSecond = accounts.get(uuidSecond);

	assertThat(retrievedFirst.isPresent()).isTrue();
	assertThat(retrievedSecond.isPresent()).isTrue();

	verifyStoredState("johndoe", uuidFirst, retrievedFirst.get(), accountFirst);
	verifyStoredState("+14152221111", uuidSecond, retrievedSecond.get(), accountSecond);
    }

    @Ignore //
    @Test
    public void testOverwrite() throws Exception {
	Device device = generateDevice(1);
	UUID firstUuid = UUID.randomUUID();
	Account account = generateAccount("johndoe", firstUuid, Collections.singleton(device));

	long directoryVersion = 10;

	accounts.create(account, directoryVersion);

	PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM accounts WHERE number = ?");
	verifyStoredState(statement, "johndoe", account.getUuid(), account);

	UUID secondUuid = UUID.randomUUID();

	device = generateDevice(1);
	account = generateAccount("johndoe", secondUuid, Collections.singleton(device));

	accounts.create(account, directoryVersion + 1L);
	verifyStoredState(statement, "johndoe", firstUuid, account);
    }

    @Ignore //
    @Test
    public void testUpdate() {
	Device device = generateDevice(1);
	Account account = generateAccount("johndoe", UUID.randomUUID(), Collections.singleton(device));

	long directoryVersion = 10;

	accounts.create(account, directoryVersion);

	device.setName("foobar");

	accounts.update(account);

	Optional<Account> retrieved = accounts.get("johndoe");

	assertThat(retrieved.isPresent()).isTrue();
	verifyStoredState("johndoe", account.getUuid(), retrieved.get(), account);

	retrieved = accounts.get(account.getUuid());

	assertThat(retrieved.isPresent()).isTrue();
	verifyStoredState("johndoe", account.getUuid(), retrieved.get(), account);
    }

    @Ignore //
    @Test
    public void testRetrieveFrom() {
	List<Account> users = new ArrayList<>();
	long directoryVersion = 10;

	for (int i = 1; i <= 100; i++) {
	    Account account = generateAccount("+1" + String.format("%03d", i), UUID.randomUUID());
	    users.add(account);
	    accounts.create(account, directoryVersion);
	    directoryVersion++;
	}

	users.sort((account, t1) -> UUIDComparator.staticCompare(account.getUuid(), t1.getUuid()));

	List<Account> retrieved = accounts.getAllFrom(10);
	assertThat(retrieved.size()).isEqualTo(10);

	for (int i = 0; i < retrieved.size(); i++) {
	    verifyStoredState(users.get(i).getUserLogin(), users.get(i).getUuid(), retrieved.get(i), users.get(i));
	}

	for (int j = 0; j < 9; j++) {
	    retrieved = accounts.getAllFrom(retrieved.get(9).getUuid(), 10);
	    assertThat(retrieved.size()).isEqualTo(10);

	    for (int i = 0; i < retrieved.size(); i++) {
		verifyStoredState(users.get(10 + (j * 10) + i).getUserLogin(), users.get(10 + (j * 10) + i).getUuid(), retrieved.get(i), users.get(10 + (j * 10) + i));
	    }
	}
    }

    @Test
    public void testDelete() {
	final Device deletedDevice = generateDevice(1);
	final Account deletedAccount = generateAccount("+14151112222", UUID.randomUUID(), Collections.singleton(deletedDevice));
	final Device retainedDevice = generateDevice(1);
	final Account retainedAccount = generateAccount("+14151112345", UUID.randomUUID(), Collections.singleton(retainedDevice));

	long directoryVersion = 10;

	accounts.create(deletedAccount, directoryVersion);
	accounts.create(retainedAccount, directoryVersion);

	assertThat(accounts.get(deletedAccount.getUuid())).isPresent();
	assertThat(accounts.get(retainedAccount.getUuid())).isPresent();

	accounts.delete(deletedAccount.getUuid(), directoryVersion);

	assertThat(accounts.get(deletedAccount.getUuid())).isNotPresent();

	verifyStoredState(retainedAccount.getUserLogin(), retainedAccount.getUuid(), accounts.get(retainedAccount.getUuid()).get(), retainedAccount);
    }

    @Ignore //
    @Test
    public void testVacuum() {
	Device device = generateDevice(1);
	Account account = generateAccount("johndoe", UUID.randomUUID(), Collections.singleton(device));

	long directoryVersion = 10;

	accounts.create(account, directoryVersion);
	accounts.vacuum();

	Optional<Account> retrieved = accounts.get("johndoe");
	assertThat(retrieved.isPresent()).isTrue();

	verifyStoredState("johndoe", account.getUuid(), retrieved.get(), account);
    }

    @Ignore //
    @Test
    public void testMissing() {
	Device device = generateDevice(1);
	Account account = generateAccount("johndoe", UUID.randomUUID(), Collections.singleton(device));

	long directoryVersion = 10;

	accounts.create(account, directoryVersion);

	Optional<Account> retrieved = accounts.get("+11111111");
	assertThat(retrieved.isPresent()).isFalse();

	retrieved = accounts.get(UUID.randomUUID());
	assertThat(retrieved.isPresent()).isFalse();
    }

    @Ignore //
    @Test
    public void testBreaker() throws InterruptedException {
	Jdbi jdbi = mock(Jdbi.class);
	doThrow(new TransactionException("Database error!")).when(jdbi).useHandle(any(HandleConsumer.class));

	CircuitBreakerConfiguration configuration = new CircuitBreakerConfiguration();
	configuration.setWaitDurationInOpenStateInSeconds(1);
	configuration.setRingBufferSizeInHalfOpenState(1);
	configuration.setRingBufferSizeInClosedState(2);
	configuration.setFailureRateThreshold(50);

	Accounts accounts = new Accounts(new FaultTolerantDatabase("testAccountBreaker", jdbi, configuration));
	Account account = generateAccount("johndoe", UUID.randomUUID());

	try {
	    accounts.update(account);
	    throw new AssertionError();
	} catch (TransactionException e) {
	    // good
	}

	try {
	    accounts.update(account);
	    throw new AssertionError();
	} catch (TransactionException e) {
	    // good
	}

	try {
	    accounts.update(account);
	    throw new AssertionError();
	} catch (CallNotPermittedException e) {
	    // good
	}

	Thread.sleep(1100);

	try {
	    accounts.update(account);
	    throw new AssertionError();
	} catch (TransactionException e) {
	    // good
	}

    }

    private Device generateDevice(long id) {
	Random random = new Random(System.currentTimeMillis());
	SignedPreKey signedPreKey = new SignedPreKey(random.nextInt(), "testPublicKey-" + random.nextInt(), "testSignature-" + random.nextInt());
	return new Device(id, "testName-" + random.nextInt(), "testAuthToken-" + random.nextInt(), "testSalt-" + random.nextInt(), null, "testGcmId-" + random.nextInt(), "testApnId-" + random.nextInt(), "testVoipApnId-" + random.nextInt(), random.nextBoolean(), random.nextInt(), signedPreKey, random.nextInt(), random.nextInt(), "testUserAgent-" + random.nextInt() , 0, new Device.DeviceCapabilities(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean(), random.nextBoolean()));

    }

    private Account generateAccount(String number, UUID uuid) {
	Device device = generateDevice(1);
	return generateAccount(number, uuid, Collections.singleton(device));
    }

    private Account generateAccount(String number, UUID uuid, Set<Device> devices) {
	byte[] unidentifiedAccessKey = new byte[16];
	Random random = new Random(System.currentTimeMillis());
	Arrays.fill(unidentifiedAccessKey, (byte) random.nextInt(255));

	return new Account(number, uuid, devices, unidentifiedAccessKey);
    }

    private void verifyStoredState(PreparedStatement statement, String number, UUID uuid, Account expecting)
	    throws SQLException, IOException {
	statement.setString(1, number);

	ResultSet resultSet = statement.executeQuery();

	if (resultSet.next()) {
	    String data = resultSet.getString("data");
	    assertThat(data).isNotEmpty();

	    Account result = new AccountRowMapper().map(resultSet, null);
	    verifyStoredState(number, uuid, result, expecting);
	} else {
	    throw new AssertionError("No data");
	}

	assertThat(resultSet.next()).isFalse();
    }

    private void verifyStoredState(String number, UUID uuid, Account result, Account expecting) {
	assertThat(result.getUserLogin()).isEqualTo(number);
	assertThat(result.getLastSeen()).isEqualTo(expecting.getLastSeen());
	assertThat(result.getUuid()).isEqualTo(uuid);
	assertThat(Arrays.equals(result.getUnidentifiedAccessKey().get(), expecting.getUnidentifiedAccessKey().get())).isTrue();

	for (Device expectingDevice : expecting.getDevices()) {
	    Device resultDevice = result.getDevice(expectingDevice.getId()).get();
	    assertThat(resultDevice.getApnId()).isEqualTo(expectingDevice.getApnId());
	    assertThat(resultDevice.getGcmId()).isEqualTo(expectingDevice.getGcmId());
	    assertThat(resultDevice.getLastSeen()).isEqualTo(expectingDevice.getLastSeen());
	    assertThat(resultDevice.getSignedPreKey().getPublicKey()).isEqualTo(expectingDevice.getSignedPreKey().getPublicKey());
	    assertThat(resultDevice.getSignedPreKey().getKeyId()).isEqualTo(expectingDevice.getSignedPreKey().getKeyId());
	    assertThat(resultDevice.getSignedPreKey().getSignature()).isEqualTo(expectingDevice.getSignedPreKey().getSignature());
	    assertThat(resultDevice.getFetchesMessages()).isEqualTo(expectingDevice.getFetchesMessages());
	    assertThat(resultDevice.getUserAgent()).isEqualTo(expectingDevice.getUserAgent());
	    assertThat(resultDevice.getName()).isEqualTo(expectingDevice.getName());
	    assertThat(resultDevice.getCreated()).isEqualTo(expectingDevice.getCreated());
	}
    }
}