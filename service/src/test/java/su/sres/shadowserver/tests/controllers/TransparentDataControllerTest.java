package su.sres.shadowserver.tests.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import su.sres.shadowserver.controllers.TransparentDataController;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.mappers.RateLimitExceededExceptionMapper;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.PublicAccount;
import su.sres.shadowserver.tests.util.AuthHelper;
import su.sres.shadowserver.util.SystemMapper;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.testing.junit.ResourceTestRule;
import static junit.framework.TestCase.*;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static su.sres.shadowserver.tests.util.JsonHelpers.asJson;
import static su.sres.shadowserver.tests.util.JsonHelpers.jsonFixture;

public class TransparentDataControllerTest {

  private final AccountsManager     accountsManager = mock(AccountsManager.class);
  private final Map<String, String> indexMap        = new HashMap<>();

  @Rule
  public final ResourceTestRule resources = ResourceTestRule.builder()
                                                            .addProvider(AuthHelper.getAuthFilter())
                                                            .addProvider(new AuthValueFactoryProvider.Binder<>(Account.class))
                                                            .addProvider(new RateLimitExceededExceptionMapper())
                                                            .setMapper(SystemMapper.getMapper())
                                                            .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
                                                            .addResource(new TransparentDataController(accountsManager, indexMap))
                                                            .build();


  @Before
  public void setup() {
    Account accountOne = new Account("+14151231111", Collections.singleton(new Device(1, "foo", "bar", "salt", "keykey", "gcm-id", "apn-id", "voipapn-id", true, 1234, new SignedPreKey(5, "public-signed", "signtture-signed"), 31337, 31336, "CoolClient", true)), new byte[16]);
    Account accountTwo = new Account("+14151232222", Collections.singleton(new Device(1, "2foo", "2bar", "2salt", "2keykey", "2gcm-id", "2apn-id", "2voipapn-id", true, 1234, new SignedPreKey(5, "public-signed", "signtture-signed"), 31337, 31336, "CoolClient", true)), new byte[16]);

    accountOne.setProfileName("OneProfileName");
    accountOne.setIdentityKey("identity_key_value");
    accountTwo.setProfileName("TwoProfileName");
    accountTwo.setIdentityKey("different_identity_key_value");


    indexMap.put("1", "+14151231111");
    indexMap.put("2", "+14151232222");

    when(accountsManager.get(eq("+14151231111"))).thenReturn(Optional.of(accountOne));
    when(accountsManager.get(eq("+14151232222"))).thenReturn(Optional.of(accountTwo));
  }

  @Test
  public void testAccountOne() throws IOException {
    Response response = resources.getJerseyTest()
                                 .target(String.format("/v1/transparency/account/%s", "1"))
                                 .request()
                                 .get();

    assertEquals(200, response.getStatus());

    Account result = response.readEntity(PublicAccount.class);

    assertTrue(result.getPin().isPresent());
    assertEquals("******", result.getPin().get());
    assertNull(result.getNumber());
    assertEquals("OneProfileName", result.getProfileName());

    assertThat("Account serialization works",
               asJson(result),
               is(equalTo(jsonFixture("fixtures/transparent_account.json"))));

    verify(accountsManager, times(1)).get(eq("+14151231111"));
    verifyNoMoreInteractions(accountsManager);
  }

  @Test
  public void testAccountTwo() throws IOException {
    Response response = resources.getJerseyTest()
                                 .target(String.format("/v1/transparency/account/%s", "2"))
                                 .request()
                                 .get();

    assertEquals(200, response.getStatus());

    Account result = response.readEntity(PublicAccount.class);

    assertTrue(result.getPin().isPresent());
    assertEquals("******", result.getPin().get());
    assertNull(result.getNumber());
    assertEquals("TwoProfileName", result.getProfileName());

    assertThat("Account serialization works 2",
               asJson(result),
               is(equalTo(jsonFixture("fixtures/transparent_account2.json"))));

    verify(accountsManager, times(1)).get(eq("+14151232222"));
  }

  @Test
  public void testAccountMissing() {
    Response response = resources.getJerseyTest()
                                 .target(String.format("/v1/transparency/account/%s", "3"))
                                 .request()
                                 .get();

    assertEquals(404, response.getStatus());
    verifyNoMoreInteractions(accountsManager);
  }

}