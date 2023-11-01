/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.dropwizard.auth.Auth;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.auth.BasicAuthorizationHeader;
import su.sres.shadowserver.auth.InvalidAuthorizationHeaderException;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.entities.DeviceInfo;
import su.sres.shadowserver.entities.DeviceInfoList;
import su.sres.shadowserver.entities.DeviceResponse;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.Device.DeviceCapabilities;
import su.sres.shadowserver.storage.KeysScyllaDb;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.StoredVerificationCodeManager;
import su.sres.shadowserver.util.Util;
import su.sres.shadowserver.util.VerificationCode;
import su.sres.shadowserver.util.ua.UnrecognizedUserAgentException;
import su.sres.shadowserver.util.ua.UserAgentUtil;

@Path("/v1/devices")
public class DeviceController {

  private static final int MAX_DEVICES = 6;

  private final StoredVerificationCodeManager pendingDevices;
  private final AccountsManager accounts;
  private final MessagesManager messages;
  private final KeysScyllaDb keys;
  private final RateLimiters rateLimiters;
  private final Map<String, Integer> maxDeviceConfiguration;
  private final int verificationCodeLifetime;

  public DeviceController(StoredVerificationCodeManager pendingDevices,
      AccountsManager accounts,
      MessagesManager messages,
      KeysScyllaDb keys,
      RateLimiters rateLimiters,
      Map<String, Integer> maxDeviceConfiguration,
      int verificationCodeLifetime) {
    this.pendingDevices = pendingDevices;
    this.accounts = accounts;
    this.messages = messages;
    this.keys = keys;
    this.rateLimiters = rateLimiters;
    this.maxDeviceConfiguration = maxDeviceConfiguration;
    this.verificationCodeLifetime = verificationCodeLifetime;
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public DeviceInfoList getDevices(@Auth AuthenticatedAccount auth) {
    List<DeviceInfo> devices = new LinkedList<>();

    for (Device device : auth.getAccount().getDevices()) {
      devices.add(new DeviceInfo(device.getId(), device.getName(),
          device.getLastSeen(), device.getCreated()));
    }

    return new DeviceInfoList(devices);
  }

  @Timed
  @DELETE
  @Path("/{device_id}")
  public void removeDevice(@Auth AuthenticatedAccount auth, @PathParam("device_id") long deviceId) {
    Account account = auth.getAccount();
    if (auth.getAuthenticatedDevice().getId() != Device.MASTER_ID) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    messages.clear(account.getUuid(), deviceId);
    account = accounts.update(account, a -> a.removeDevice(deviceId));
    keys.delete(account.getUuid(), deviceId);
    // ensure any messages that came in after the first clear() are also removed
    messages.clear(account.getUuid(), deviceId);
  }

  @Timed
  @GET
  @Path("/provisioning/code")
  @Produces(MediaType.APPLICATION_JSON)
  public VerificationCode createDeviceToken(@Auth AuthenticatedAccount auth)
      throws RateLimitExceededException, DeviceLimitExceededException {

    final Account account = auth.getAccount();
    rateLimiters.getAllocateDeviceLimiter().validate(account.getUuid());

    int maxDeviceLimit = MAX_DEVICES;

    if (maxDeviceConfiguration.containsKey(account.getUserLogin())) {
      maxDeviceLimit = maxDeviceConfiguration.get(account.getUserLogin());
    }

    if (account.getEnabledDeviceCount() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.getDevices().size(), MAX_DEVICES);
    }

    if (auth.getAuthenticatedDevice().getId() != Device.MASTER_ID) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    VerificationCode verificationCode = generateVerificationCode();
    StoredVerificationCode storedVerificationCode = new StoredVerificationCode(verificationCode.getVerificationCode(),
        System.currentTimeMillis(),
        null);

    pendingDevices.store(account.getUserLogin(), storedVerificationCode);

    return verificationCode;
  }

  @Timed
  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/{verification_code}")
  public DeviceResponse verifyDeviceToken(@PathParam("verification_code") String verificationCode,
      @HeaderParam("Authorization") BasicAuthorizationHeader authorizationHeader,
      @HeaderParam("User-Agent") String userAgent,
      @Valid AccountAttributes accountAttributes)
      throws RateLimitExceededException, DeviceLimitExceededException {

    String userLogin = authorizationHeader.getUsername();
    String password = authorizationHeader.getPassword();

    rateLimiters.getVerifyDeviceLimiter().validate(userLogin);

    Optional<StoredVerificationCode> storedVerificationCode = pendingDevices.getCodeForUserLogin(userLogin);

    if (!storedVerificationCode.isPresent() || !storedVerificationCode.get().isValid(verificationCode, verificationCodeLifetime)) {
      throw new WebApplicationException(Response.status(403).build());
    }

    Optional<Account> account = accounts.get(userLogin);

    if (!account.isPresent()) {
      throw new WebApplicationException(Response.status(403).build());
    }

    int maxDeviceLimit = MAX_DEVICES;

    if (maxDeviceConfiguration.containsKey(account.get().getUserLogin())) {
      maxDeviceLimit = maxDeviceConfiguration.get(account.get().getUserLogin());
    }

    if (account.get().getEnabledDeviceCount() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.get().getDevices().size(), MAX_DEVICES);
    }

    final DeviceCapabilities capabilities = accountAttributes.getCapabilities();
    if (capabilities != null && isCapabilityDowngrade(account.get(), capabilities, userAgent)) {
      throw new WebApplicationException(Response.status(409).build());
    }

    Device device = new Device();
    device.setName(accountAttributes.getName());
    device.setAuthenticationCredentials(new AuthenticationCredentials(password));
    device.setFetchesMessages(accountAttributes.getFetchesMessages());
    device.setRegistrationId(accountAttributes.getRegistrationId());
    device.setLastSeen(Util.todayInMillis());
    device.setCreated(System.currentTimeMillis());
    device.setCapabilities(accountAttributes.getCapabilities());

    accounts.update(account.get(), a -> {
      device.setId(a.getNextDeviceId());
      messages.clear(a.getUuid(), device.getId());
      a.addDevice(device);
    });

    pendingDevices.remove(userLogin);

    return new DeviceResponse(device.getId());
  }

  @Timed
  @PUT
  @Path("/unauthenticated_delivery")
  public void setUnauthenticatedDelivery(@Auth AuthenticatedAccount auth) {
    assert (auth.getAuthenticatedDevice() != null);
    // Deprecated
  }

  @Timed
  @PUT
  @Path("/capabilities")
  public void setCapabiltities(@Auth AuthenticatedAccount auth, @Valid DeviceCapabilities capabilities) {
    assert (auth.getAuthenticatedDevice() != null);
    final long deviceId = auth.getAuthenticatedDevice().getId();
    accounts.updateDevice(auth.getAccount(), deviceId, d -> d.setCapabilities(capabilities));
  }

  @VisibleForTesting
  protected VerificationCode generateVerificationCode() {
    SecureRandom random = new SecureRandom();
    int randomInt = 100000 + random.nextInt(900000);
    return new VerificationCode(randomInt);
  }

  private boolean isCapabilityDowngrade(Account account, DeviceCapabilities capabilities, String userAgent) {
    boolean isDowngrade = false;

    isDowngrade |= account.isChangeUserLoginSupported() && !capabilities.isChangeUserLogin();
    isDowngrade |= account.isAnnouncementGroupSupported() && !capabilities.isAnnouncementGroup();
    isDowngrade |= account.isSenderKeySupported() && !capabilities.isSenderKey();
    isDowngrade |= account.isGv1MigrationSupported() && !capabilities.isGv1Migration();

    if (account.isGroupsV2Supported()) {
      try {
        switch (UserAgentUtil.parseUserAgentString(userAgent).getPlatform()) {
        case DESKTOP:
        case ANDROID: {
          if (!capabilities.isGv2_3()) {
            isDowngrade = true;
          }

          break;
        }

        case IOS: {
          if (!capabilities.isGv2_2() && !capabilities.isGv2_3()) {
            isDowngrade = true;
          }

          break;
        }

        }
      } catch (final UnrecognizedUserAgentException e) {
        // If we can't parse the UA string, the client is for sure too old to support
        // groups V2
        isDowngrade = true;
      }
    }

    return isDowngrade;
  }
}
