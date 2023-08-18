/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit5.EmbeddedPostgresExtension;
import com.opentable.db.postgres.junit5.PreparedDbExtension;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class PendingAccountsTest {

  @RegisterExtension
  static PreparedDbExtension db = EmbeddedPostgresExtension.preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

  private PendingAccounts pendingAccounts;

  @BeforeEach
  void setupAccountsDao() {
    this.pendingAccounts = new PendingAccounts(new FaultTolerantDatabase("pending_accounts-test", Jdbi.create(db.getTestDatabase()), new CircuitBreakerConfiguration()));
  }

  @Test
  void testStore() throws SQLException {
    pendingAccounts.insert("alice", "1234", 1111, null);

    PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM pending_accounts WHERE number = ?");
    statement.setString(1, "alice");

    ResultSet resultSet = statement.executeQuery();

    if (resultSet.next()) {
      assertThat(resultSet.getString("verification_code")).isEqualTo("1234");
      assertThat(resultSet.getLong("timestamp")).isEqualTo(1111);
      assertThat(resultSet.getString("push_code")).isNull();
    } else {
      throw new AssertionError("no results");
    }

    assertThat(resultSet.next()).isFalse();
  }

  @Test
  void testStoreWithPushChallenge() throws SQLException {
    pendingAccounts.insert("alice", null, 1111, "112233");

    PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM pending_accounts WHERE number = ?");
    statement.setString(1, "alice");

    ResultSet resultSet = statement.executeQuery();

    if (resultSet.next()) {
      assertThat(resultSet.getString("verification_code")).isNull();
      assertThat(resultSet.getLong("timestamp")).isEqualTo(1111);
      assertThat(resultSet.getString("push_code")).isEqualTo("112233");
    } else {
      throw new AssertionError("no results");
    }

    assertThat(resultSet.next()).isFalse();
  }

  @Test
  void testRetrieve() throws Exception {
    pendingAccounts.insert("alice", "4321", 2222, null);
    pendingAccounts.insert("bob", "1212", 5555, null);

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("alice");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);

    Optional<StoredVerificationCode> missingCode = pendingAccounts.getCodeForUserLogin("kat");
    assertThat(missingCode.isPresent()).isFalse();
  }

  @Test
  void testRetrieveWithPushChallenge() throws Exception {
    pendingAccounts.insert("alice", "4321", 2222, "bar");
    pendingAccounts.insert("bob", "1212", 5555, "bang");

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("alice");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);
    assertThat(verificationCode.get().getPushCode()).isEqualTo("bar");

    Optional<StoredVerificationCode> missingCode = pendingAccounts.getCodeForUserLogin("kat");
    assertThat(missingCode.isPresent()).isFalse();
  }

  @Test
  void testOverwrite() throws Exception {
    pendingAccounts.insert("alice", "4321", 2222, null);
    pendingAccounts.insert("alice", "4444", 3333, null);

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("alice");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
  }

  @Test
  void testOverwriteWithPushToken() throws Exception {
    pendingAccounts.insert("alice", "4321", 2222, "bar");
    pendingAccounts.insert("alice", "4444", 3333, "bang");

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("alice");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
    assertThat(verificationCode.get().getPushCode()).isEqualTo("bang");
  }

  @Test
  void testVacuum() {
    pendingAccounts.insert("alice", "4321", 2222, null);
    pendingAccounts.insert("alice", "4444", 3333, null);
    pendingAccounts.vacuum();

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("alice");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
  }

  @Test
  void testRemove() {
    pendingAccounts.insert("alice", "4321", 2222, "bar");
    pendingAccounts.insert("bob", "1212", 5555, null);

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("alice");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);

    pendingAccounts.remove("alice");

    verificationCode = pendingAccounts.getCodeForUserLogin("alice");
    assertThat(verificationCode.isPresent()).isFalse();

    verificationCode = pendingAccounts.getCodeForUserLogin("bob");
    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("1212");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(5555);
    assertThat(verificationCode.get().getPushCode()).isNull();
  }
}