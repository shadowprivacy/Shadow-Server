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
import su.sres.shadowserver.storage.PendingDevices;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class PendingDevicesTest {

  @Rule
  public PreparedDbRule db = EmbeddedPostgresRules.preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

  private PendingDevices pendingDevices;

  @Before
  public void setupAccountsDao() {
	  this.pendingDevices = new PendingDevices(new FaultTolerantDatabase("peding_devices-test", Jdbi.create(db.getTestDatabase()), new CircuitBreakerConfiguration()));
  }

  @Ignore //
  @Test
  public void testStore() throws SQLException {
    pendingDevices.insert("+14151112222", "1234", 1111);

    PreparedStatement statement = db.getTestDatabase().getConnection().prepareStatement("SELECT * FROM pending_devices WHERE number = ?");
    statement.setString(1, "+14151112222");

    ResultSet resultSet = statement.executeQuery();

    if (resultSet.next()) {
      assertThat(resultSet.getString("verification_code")).isEqualTo("1234");
      assertThat(resultSet.getLong("timestamp")).isEqualTo(1111);
    } else {
      throw new AssertionError("no results");
    }

    assertThat(resultSet.next()).isFalse();
  }

  @Test
  @Ignore
  public void testRetrieve() throws Exception {
    pendingDevices.insert("+14151112222", "4321", 2222);
    pendingDevices.insert("+14151113333", "1212", 5555);

    Optional<StoredVerificationCode> verificationCode = pendingDevices.getCodeForNumber("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);

    Optional<StoredVerificationCode> missingCode = pendingDevices.getCodeForNumber("+11111111111");
    assertThat(missingCode.isPresent()).isFalse();
  }

  @Test
  @Ignore
  public void testOverwrite() throws Exception {
    pendingDevices.insert("+14151112222", "4321", 2222);
    pendingDevices.insert("+14151112222", "4444", 3333);

    Optional<StoredVerificationCode> verificationCode = pendingDevices.getCodeForNumber("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4444");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(3333);
  }

  @Test
  @Ignore
  public void testRemove() {
    pendingDevices.insert("+14151112222", "4321", 2222);
    pendingDevices.insert("+14151113333", "1212", 5555);

    Optional<StoredVerificationCode> verificationCode = pendingDevices.getCodeForNumber("+14151112222");

    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("4321");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(2222);

    pendingDevices.remove("+14151112222");

    verificationCode = pendingDevices.getCodeForNumber("+14151112222");
    assertThat(verificationCode.isPresent()).isFalse();

    verificationCode = pendingDevices.getCodeForNumber("+14151113333");
    assertThat(verificationCode.isPresent()).isTrue();
    assertThat(verificationCode.get().getCode()).isEqualTo("1212");
    assertThat(verificationCode.get().getTimestamp()).isEqualTo(5555);
  }

}