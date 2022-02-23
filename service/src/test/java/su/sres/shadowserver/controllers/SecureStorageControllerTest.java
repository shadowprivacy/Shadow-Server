package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.ClassRule;
import org.junit.Test;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.ExternalServiceCredentialGenerator;
import su.sres.shadowserver.auth.ExternalServiceCredentials;
import su.sres.shadowserver.controllers.SecureStorageController;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.SystemMapper;

import javax.ws.rs.core.Response;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit.ResourceTestRule;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class SecureStorageControllerTest {

  private static final ExternalServiceCredentialGenerator storageCredentialGenerator = new ExternalServiceCredentialGenerator(new byte[32], new byte[32], false);

  @ClassRule
  public static final ResourceTestRule resources = ResourceTestRule.builder()
                                                                   .addProvider(AuthHelper.getAuthFilter())
                                                                   .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(Account.class, DisabledPermittedAccount.class)))
                                                                   .setMapper(SystemMapper.getMapper())
                                                                   .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
                                                                   .addResource(new SecureStorageController(storageCredentialGenerator))
                                                                   .build();


  @Test
  public void testGetCredentials() throws Exception {
    ExternalServiceCredentials credentials = resources.getJerseyTest()
                                                      .target("/v1/storage/auth")
                                                      .request()
                                                      .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                                                      .get(ExternalServiceCredentials.class);

    assertThat(credentials.getPassword()).isNotEmpty();
    assertThat(credentials.getUsername()).isNotEmpty();
  }

  @Test
  public void testGetCredentialsBadAuth() throws Exception {
    Response response = resources.getJerseyTest()
                                 .target("/v1/storage/auth")
                                 .request()
                                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.INVVALID_NUMBER, AuthHelper.INVALID_PASSWORD))
                                 .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }


}