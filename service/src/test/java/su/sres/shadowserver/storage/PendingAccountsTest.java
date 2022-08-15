/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit.EmbeddedPostgresRules;
import com.opentable.db.postgres.junit.PreparedDbRule;
import org.jdbi.v3.core.Jdbi;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.storage.FaultTolerantDatabase;
import su.sres.shadowserver.storage.PendingAccounts;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class PendingAccountsTest {

  @Rule
  public PreparedDbRule db = EmbeddedPostgresRules.preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

  private PendingAccounts pendingAccounts;

  @Before
  public void setupAccountsDao() {
	  this.pendingAccounts = new PendingAccounts(new FaultTolerantDatabase("pending_accounts-test", Jdbi.create(db.getTestDatabase()), new CircuitBreakerConfiguration()));
  }

  @Ignore //
  @Test
  public void testStore() throws SQLException {
	  pendingAccounts.insert("+14151112222", "1234", 1111, null);

    PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM pending_accounts WHERE number = ?");
    statement.setString(1, "+14151112222");

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

  @Ignore //
  @Test
  public void testStoreWithPushChallenge() throws SQLException {
    pendingAccounts.insert("+14151112222", null, 1111,  "112233");

    PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM pending_accounts WHERE number = ?");
    statement.setString(1, "+14151112222");

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
  @Ignore
  public void testRetrieve() throws Exception {
	  pendingAccounts.insert("+14151112222", "4321", 2222, null);
	    pendingAccounts.insert("+14151113333", "1212", 5555, null);

	    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");

	    assertThat(verificationCode.isPresent()).isTrue();
	    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
	    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);

	    Optional<StoredVerificationCode> missingCode = pendingAccounts.getCodeForUserLogin("+11111111111");
	    assertThat(missingCode.isPresent()).isFalse();
  }

  @Test
  @Ignore
  public void testRetrieveWithPushChallenge() throws Exception {
	    pendingAccounts.insert("+14151112222", "4321", 2222, "bar");
	    pendingAccounts.insert("+14151113333", "1212", 5555, "bang");

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);
    assertThat(verificationCode.get().getPushCode()).isEqualTo("bar");

    Optional<StoredVerificationCode> missingCode = pendingAccounts.getCodeForUserLogin("+11111111111");
    assertThat(missingCode.isPresent()).isFalse();
  }

  @Test
  @Ignore
  public void testOverwrite() throws Exception {
	  pendingAccounts.insert("+14151112222", "4321", 2222, null);
	    pendingAccounts.insert("+14151112222", "4444", 3333, null);

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
  }
  
  @Test
  @Ignore
  public void testOverwriteWithPushToken() throws Exception {
    pendingAccounts.insert("+14151112222", "4321", 2222, "bar");
    pendingAccounts.insert("+14151112222", "4444", 3333, "bang");

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
    assertThat(verificationCode.get().getPushCode()).isEqualTo("bang");
  }

  @Test
  @Ignore
  public void testVacuum() {
	  pendingAccounts.insert("+14151112222", "4321", 2222, null);
	    pendingAccounts.insert("+14151112222", "4444", 3333, null);
    pendingAccounts.vacuum();

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
  }

  @Test
  @Ignore
  public void testRemove() {
	  pendingAccounts.insert("+14151112222", "4321", 2222, "bar");
	    pendingAccounts.insert("+14151113333", "1212", 5555, null);

    Optional<StoredVerificationCode> verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);

    pendingAccounts.remove("+14151112222");

    verificationCode = pendingAccounts.getCodeForUserLogin("+14151112222");
    assertThat(verificationCode.isPresent()).isFalse();

    verificationCode = pendingAccounts.getCodeForUserLogin("+14151113333");
    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("1212");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(5555);
    assertThat(verificationCode.get().getPushCode()).isNull();
  }


}