package su.sres.shadowserver.tests.storage;

import com.opentable.db.postgres.embedded.LiquibasePreparer;
import com.opentable.db.postgres.junit.EmbeddedPostgresRules;
import com.opentable.db.postgres.junit.PreparedDbRule;
import org.jdbi.v3.core.Jdbi;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import su.sres.shadowserver.configuration.CircuitBreakerConfiguration;
import su.sres.shadowserver.storage.FaultTolerantDatabase;
import su.sres.shadowserver.storage.RemoteConfig;
import su.sres.shadowserver.storage.RemoteConfigs;
import su.sres.shadowserver.storage.RemoteConfigsManager;
import su.sres.shadowserver.tests.util.AuthHelper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class RemoteConfigsManagerTest {

	  @Rule
	  public PreparedDbRule db = EmbeddedPostgresRules.preparedDatabase(LiquibasePreparer.forClasspathLocation("accountsdb.xml"));

	  private RemoteConfigsManager remoteConfigs;

	  @Before
	  public void setup() {
	    RemoteConfigs remoteConfigs = new RemoteConfigs(new FaultTolerantDatabase("remote_configs-test", Jdbi.create(db.getTestDatabase()), new CircuitBreakerConfiguration()));
	    this.remoteConfigs = new RemoteConfigsManager(remoteConfigs, 500);
	    this.remoteConfigs.start();
	  }

	  @Ignore //
	  @Test
	  public void testUpdate() throws InterruptedException {
		  remoteConfigs.set(new RemoteConfig("android.stickers", 50, Set.of(AuthHelper.VALID_UUID), "FALSE", "TRUE"));
		    remoteConfigs.set(new RemoteConfig("value.sometimes", 50, Set.of(), "bar", "baz"));
		    remoteConfigs.set(new RemoteConfig("ios.stickers", 50, Set.of(), "FALSE", "TRUE"));
		    remoteConfigs.set(new RemoteConfig("ios.stickers", 75, Set.of(), "FALSE", "TRUE"));
		    remoteConfigs.set(new RemoteConfig("value.sometimes", 25, Set.of(AuthHelper.VALID_UUID), "abc", "def"));

	    Thread.sleep(501);

	    List<RemoteConfig> results = remoteConfigs.getAll();

	    assertThat(results.size()).isEqualTo(3);
	    assertThat(results.get(0).getName()).isEqualTo("android.stickers");
	    assertThat(results.get(0).getPercentage()).isEqualTo(50);
	    assertThat(results.get(0).getUuids().size()).isEqualTo(1);
	    assertThat(results.get(0).getUuids().contains(AuthHelper.VALID_UUID)).isTrue();

	    assertThat(results.get(1).getName()).isEqualTo("ios.stickers");
	    assertThat(results.get(1).getPercentage()).isEqualTo(75);
	    assertThat(results.get(1).getUuids()).isEmpty();
	    
	    assertThat(results.get(2).getName()).isEqualTo("value.sometimes");
	    assertThat(results.get(2).getUuids()).hasSize(1);
	    assertThat(results.get(2).getUuids()).contains(AuthHelper.VALID_UUID);
	    assertThat(results.get(2).getPercentage()).isEqualTo(25);
	    assertThat(results.get(2).getDefaultValue()).isEqualTo("abc");
	    assertThat(results.get(2).getValue()).isEqualTo("def");

	  }

	}