/**
 * Copyright (C) 2014 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.shadowserver.tests.controllers;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.Path;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit.ResourceTestRule;

import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.controllers.DeviceController;
import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.entities.DeviceResponse;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.mappers.DeviceLimitExceededExceptionMapper;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.PendingDevicesManager;
import su.sres.shadowserver.tests.util.AuthHelper;
import su.sres.shadowserver.util.VerificationCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class DeviceControllerTest {
  @Path("/v1/devices")
  static class DumbVerificationDeviceController extends DeviceController {
    public DumbVerificationDeviceController(PendingDevicesManager pendingDevices,
                                            AccountsManager accounts,
                                            MessagesManager messages,
                                            RateLimiters rateLimiters,
                                            Map<String, Integer> deviceConfiguration,
                                            int verificationCodeLifetime)
    {
      super(pendingDevices, accounts, messages, rateLimiters, deviceConfiguration, verificationCodeLifetime);
    }

    @Override
    protected VerificationCode generateVerificationCode() {
      return new VerificationCode(5678901);
    }
  }

  private PendingDevicesManager pendingDevicesManager = mock(PendingDevicesManager.class);
  private AccountsManager       accountsManager       = mock(AccountsManager.class       );
  private MessagesManager       messagesManager       = mock(MessagesManager.class);
  private RateLimiters          rateLimiters          = mock(RateLimiters.class          );
  private RateLimiter           rateLimiter           = mock(RateLimiter.class           );
  private Account               account               = mock(Account.class               );
  private Account               maxedAccount          = mock(Account.class);
  
  // introduced with CDS
  private Device                masterDevice          = mock(Device.class);

  private Map<String, Integer>  deviceConfiguration   = new HashMap<String, Integer>() {{

  }};
  
  private static final int VERIFICATION_CODE_LIFETIME = 48;

  @Rule
  public final ResourceTestRule resources = ResourceTestRule.builder()
                                                            .addProvider(AuthHelper.getAuthFilter())
                                                            .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(Account.class, DisabledPermittedAccount.class)))
                                                            .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
                                                            .addProvider(new DeviceLimitExceededExceptionMapper())
                                                            .addResource(new DumbVerificationDeviceController(pendingDevicesManager,
                                                                                                              accountsManager,
                                                                                                              messagesManager,
                                                                                                              rateLimiters,
                                                                                                              deviceConfiguration,
                                                                                                              VERIFICATION_CODE_LIFETIME))
                                                            .build();


  @Before
  public void setup() throws Exception {
    when(rateLimiters.getSmsDestinationLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getVoiceDestinationLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getVerifyLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getAllocateDeviceLimiter()).thenReturn(rateLimiter);
    when(rateLimiters.getVerifyDeviceLimiter()).thenReturn(rateLimiter);

    // introduced with CDS
    when(masterDevice.getId()).thenReturn(1L);
    
    when(account.getNextDeviceId()).thenReturn(42L);
    when(account.getUserLogin()).thenReturn(AuthHelper.VALID_NUMBER);
//    when(maxedAccount.getActiveDeviceCount()).thenReturn(6);
    
 // introduced with CDS
    when(account.getAuthenticatedDevice()).thenReturn(Optional.of(masterDevice));
    when(account.isEnabled()).thenReturn(false);

    when(pendingDevicesManager.getCodeForUserLogin(AuthHelper.VALID_NUMBER)).thenReturn(Optional.of(new StoredVerificationCode("5678901", System.currentTimeMillis(), null, VERIFICATION_CODE_LIFETIME)));
    when(pendingDevicesManager.getCodeForUserLogin(AuthHelper.VALID_NUMBER_TWO)).thenReturn(Optional.of(new StoredVerificationCode("1112223", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(31), null, VERIFICATION_CODE_LIFETIME)));
    when(accountsManager.get(AuthHelper.VALID_NUMBER)).thenReturn(Optional.of(account));
    when(accountsManager.get(AuthHelper.VALID_NUMBER_TWO)).thenReturn(Optional.of(maxedAccount));
  }

  @Test
  public void validDeviceRegisterTest() throws Exception {
    VerificationCode deviceCode = resources.getJerseyTest()
                                           .target("/v1/devices/provisioning/code")
                                           .request()
                                           .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                                           .get(VerificationCode.class);

    assertThat(deviceCode).isEqualTo(new VerificationCode(5678901));

    DeviceResponse response = resources.getJerseyTest()
                                       .target("/v1/devices/5678901")
                                       .request()
                                       .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
                                       .put(Entity.entity(new AccountAttributes("keykeykeykey", false, 1234, null),
                                                          MediaType.APPLICATION_JSON_TYPE),
                                            DeviceResponse.class);

    assertThat(response.getDeviceId()).isEqualTo(42L);

    verify(pendingDevicesManager).remove(AuthHelper.VALID_NUMBER);
    verify(messagesManager).clear(eq(AuthHelper.VALID_NUMBER), eq(42L));
  }
  
  @Test
  public void disabledDeviceRegisterTest() throws Exception {
    Response response = resources.getJerseyTest()
                                 .target("/v1/devices/provisioning/code")
                                 .request()
                                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.DISABLED_NUMBER, AuthHelper.DISABLED_PASSWORD))
                                 .get();

      assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  public void invalidDeviceRegisterTest() throws Exception {
    VerificationCode deviceCode = resources.getJerseyTest()
                                           .target("/v1/devices/provisioning/code")
                                           .request()
                                           .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, AuthHelper.VALID_PASSWORD))
                                           .get(VerificationCode.class);

    assertThat(deviceCode).isEqualTo(new VerificationCode(5678901));

    Response response = resources.getJerseyTest()
                                 .target("/v1/devices/5678902")
                                 .request()
                                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
                                 .put(Entity.entity(new AccountAttributes("keykeykeykey", false, 1234, null),
                                                    MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(403);

    verifyNoMoreInteractions(messagesManager);
  }

  @Test
  @Ignore
  public void oldDeviceRegisterTest() throws Exception {
    Response response = resources.getJerseyTest()
                                 .target("/v1/devices/1112223")
                                 .request()
                                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER_TWO, AuthHelper.VALID_PASSWORD_TWO))
                                 .put(Entity.entity(new AccountAttributes("keykeykeykey", false, 1234, null),
                                                    MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(403);

    verifyNoMoreInteractions(messagesManager);
  }

  @Test
  public void maxDevicesTest() throws Exception {
    Response response = resources.getJerseyTest()
                                 .target("/v1/devices/provisioning/code")
                                 .request()
                                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER_TWO, AuthHelper.VALID_PASSWORD_TWO))
                                 .get();

    assertEquals(411, response.getStatus());
    verifyNoMoreInteractions(messagesManager);
  }

  @Test
  public void longNameTest() throws Exception {
    Response response = resources.getJerseyTest()
                                 .target("/v1/devices/5678901")
                                 .request()
                                 .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
                                 .put(Entity.entity(new AccountAttributes("keykeykeykey", false, 1234, "this is a really long name that is longer than 80 characters it's so long that it's even longer than 204 characters. that's a lot of characters. we're talking lots and lots and lots of characters. 12345678", null, null, null),
                                                    MediaType.APPLICATION_JSON_TYPE));

    assertEquals(response.getStatus(), 422);
    verifyNoMoreInteractions(messagesManager);
  }  
  
}
