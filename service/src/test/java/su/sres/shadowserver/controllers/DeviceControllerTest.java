/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
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
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.VerificationCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(JUnitParamsRunner.class)
public class DeviceControllerTest {
  @Path("/v1/devices")
  static class DumbVerificationDeviceController extends DeviceController {
    public DumbVerificationDeviceController(PendingDevicesManager pendingDevices,
        AccountsManager accounts,
        MessagesManager messages,
        RateLimiters rateLimiters,
        Map<String, Integer> deviceConfiguration,
        int verificationCodeLifetime) {
      super(pendingDevices, accounts, messages, rateLimiters, deviceConfiguration, verificationCodeLifetime);
    }

    @Override
    protected VerificationCode generateVerificationCode() {
      return new VerificationCode(5678901);
    }
  }

  private PendingDevicesManager pendingDevicesManager = mock(PendingDevicesManager.class);
  private AccountsManager accountsManager = mock(AccountsManager.class);
  private MessagesManager messagesManager = mock(MessagesManager.class);
  private RateLimiters rateLimiters = mock(RateLimiters.class);
  private RateLimiter rateLimiter = mock(RateLimiter.class);
  private Account account = mock(Account.class);
  private Account maxedAccount = mock(Account.class);

  // introduced with CDS
  private Device masterDevice = mock(Device.class);

  private Map<String, Integer> deviceConfiguration = new HashMap<String, Integer>() {
    {
    }
  };

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
    when(account.getUuid()).thenReturn(AuthHelper.VALID_UUID);
//    when(maxedAccount.getActiveDeviceCount()).thenReturn(6);

    // introduced with CDS
    when(account.getAuthenticatedDevice()).thenReturn(Optional.of(masterDevice));
    when(account.isEnabled()).thenReturn(false);
    when(account.isGroupsV2Supported()).thenReturn(true);
    when(account.isGv1MigrationSupported()).thenReturn(true);

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
        .put(Entity.entity(new AccountAttributes(false, 1234, null,
            null, null, true, null),
            MediaType.APPLICATION_JSON_TYPE),
            DeviceResponse.class);

    assertThat(response.getDeviceId()).isEqualTo(42L);

    verify(pendingDevicesManager).remove(AuthHelper.VALID_NUMBER);
    verify(messagesManager).clear(eq(AuthHelper.VALID_UUID), eq(42L));
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
        .put(Entity.entity(new AccountAttributes(false, 1234, null, null, null, true, null),
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
        .put(Entity.entity(new AccountAttributes(false, 1234, null, null, null, true, null),
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
        .put(Entity.entity(new AccountAttributes(false, 1234, "this is a really long name that is longer than 80 characters it's so long that it's even longer than 204 characters. that's a lot of characters. we're talking lots and lots and lots of characters. 12345678", null, null, true, null), MediaType.APPLICATION_JSON_TYPE));

    assertEquals(response.getStatus(), 422);
    verifyNoMoreInteractions(messagesManager);
  }

  @Test
  @Parameters(method = "argumentsForDeviceDowngradeCapabilitiesTest")
  public void deviceDowngradeCapabilitiesTest(final String userAgent, final boolean gv2, final boolean gv2_2, final boolean gv2_3, final int expectedStatus) throws Exception {
    Device.DeviceCapabilities deviceCapabilities = new Device.DeviceCapabilities(gv2, gv2_2, gv2_3, true, false, true);
    AccountAttributes accountAttributes = new AccountAttributes(false, 1234, null, null, null, true, deviceCapabilities);
    Response response = resources.getJerseyTest()
        .target("/v1/devices/5678901")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .header("User-Agent", userAgent)
        .put(Entity.entity(accountAttributes, MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(expectedStatus);

    if (expectedStatus >= 300) {
      verifyNoMoreInteractions(messagesManager);
    }
  }

  private static Object argumentsForDeviceDowngradeCapabilitiesTest() {
    return new Object[] {
//	             User-Agent                          gv2    gv2-2  gv2-3  expected
        new Object[] { "Shadow-Android/4.68.3 Android/25", false, false, false, 409 },
        new Object[] { "Shadow-Android/4.68.3 Android/25", true, false, false, 409 },
        new Object[] { "Shadow-Android/4.68.3 Android/25", false, true, false, 409 },
        new Object[] { "Shadow-Android/4.68.3 Android/25", false, false, true, 200 },
        new Object[] { "Shadow-iOS/3.9.0", false, false, false, 409 },
        new Object[] { "Shadow-iOS/3.9.0", true, false, false, 409 },
        new Object[] { "Shadow-iOS/3.9.0", false, true, false, 200 },
        new Object[] { "Shadow-iOS/3.9.0", false, false, true, 200 },
        new Object[] { "Shadow-Desktop/1.32.0-beta.3", false, false, false, 409 },
        new Object[] { "Shadow-Desktop/1.32.0-beta.3", true, false, false, 409 },
        new Object[] { "Shadow-Desktop/1.32.0-beta.3", false, true, false, 409 },
        new Object[] { "Shadow-Desktop/1.32.0-beta.3", false, false, true, 200 },
        new Object[] { "Old client with unparsable UA", false, false, false, 409 },
        new Object[] { "Old client with unparsable UA", true, false, false, 409 },
        new Object[] { "Old client with unparsable UA", false, true, false, 409 },
        new Object[] { "Old client with unparsable UA", false, false, true, 409 }
    };
  }

  @Test
  public void deviceDowngradeGv1MigrationTest() {
    Device.DeviceCapabilities deviceCapabilities = new Device.DeviceCapabilities(true, true, true, true, false, false);
    AccountAttributes accountAttributes = new AccountAttributes(false, 1234, null, null, null, true, deviceCapabilities);
    Response response = resources.getJerseyTest()
        .target("/v1/devices/5678901")
        .request()
        .header("authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .header("user-agent", "Signal-Android/4.68.3 Android/25")
        .put(Entity.entity(accountAttributes, MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(409);

    deviceCapabilities = new Device.DeviceCapabilities(true, true, true, true, false, true);
    accountAttributes = new AccountAttributes(false, 1234, null, null, null, true, deviceCapabilities);
    response = resources.getJerseyTest()
        .target("/v1/devices/5678901")
        .request()
        .header("authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_NUMBER, "password1"))
        .header("user-agent", "Signal-Android/4.68.3 Android/25")
        .put(Entity.entity(accountAttributes, MediaType.APPLICATION_JSON_TYPE));

    assertThat(response.getStatus()).isEqualTo(200);

  }

}
