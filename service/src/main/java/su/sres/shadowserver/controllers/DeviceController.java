/**
 * Copyright (C) 2013 Open WhisperSystems
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
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.auth.AuthorizationHeader;
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
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.PendingDevicesManager;
import su.sres.shadowserver.util.Util;
import su.sres.shadowserver.util.VerificationCode;

@Path("/v1/devices")
public class DeviceController {

  private final Logger logger = LoggerFactory.getLogger(DeviceController.class);

  private static final int MAX_DEVICES = 6;

  private final PendingDevicesManager pendingDevices;
  private final AccountsManager       accounts;
  private final MessagesManager       messages;
  private final RateLimiters          rateLimiters;
  private final Map<String, Integer>  maxDeviceConfiguration;
  private final int                   verificationCodeLifetime ;

  public DeviceController(PendingDevicesManager pendingDevices,
                          AccountsManager accounts,
                          MessagesManager messages,
                          RateLimiters rateLimiters,
                          Map<String, Integer> maxDeviceConfiguration,
                          int verificationCodeLifetime)
  {
    this.pendingDevices           = pendingDevices;
    this.accounts                 = accounts;
    this.messages                 = messages;
    this.rateLimiters             = rateLimiters;
    this.maxDeviceConfiguration   = maxDeviceConfiguration;
    this.verificationCodeLifetime = verificationCodeLifetime;
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public DeviceInfoList getDevices(@Auth Account account) {
    List<DeviceInfo> devices = new LinkedList<>();

    for (Device device : account.getDevices()) {
      devices.add(new DeviceInfo(device.getId(), device.getName(),
                                 device.getLastSeen(), device.getCreated()));
    }

    return new DeviceInfoList(devices);
  }

  @Timed
  @DELETE
  @Path("/{device_id}")
  public void removeDevice(@Auth Account account, @PathParam("device_id") long deviceId) {
    if (account.getAuthenticatedDevice().get().getId() != Device.MASTER_ID) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    account.removeDevice(deviceId);
    accounts.update(account);
    messages.clear(account.getUserLogin(), deviceId);
  }

  @Timed
  @GET
  @Path("/provisioning/code")
  @Produces(MediaType.APPLICATION_JSON)
  public VerificationCode createDeviceToken(@Auth Account account)
      throws RateLimitExceededException, DeviceLimitExceededException
  {
    rateLimiters.getAllocateDeviceLimiter().validate(account.getUserLogin());

    int maxDeviceLimit = MAX_DEVICES;

    if (maxDeviceConfiguration.containsKey(account.getUserLogin())) {
      maxDeviceLimit = maxDeviceConfiguration.get(account.getUserLogin());
    }

    if (account.getEnabledDeviceCount() >= maxDeviceLimit) {
      throw new DeviceLimitExceededException(account.getDevices().size(), MAX_DEVICES);
    }

    if (account.getAuthenticatedDevice().get().getId() != Device.MASTER_ID) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    VerificationCode       verificationCode       = generateVerificationCode();
    StoredVerificationCode storedVerificationCode = new StoredVerificationCode(verificationCode.getVerificationCode(),
    		                                                                   System.currentTimeMillis(),
    		                                                                   null,
    		                                                                   verificationCodeLifetime);

    pendingDevices.store(account.getUserLogin(), storedVerificationCode);

    return verificationCode;
  }

  @Timed
  @PUT
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  @Path("/{verification_code}")
  public DeviceResponse verifyDeviceToken(@PathParam("verification_code") String verificationCode,
                                          @HeaderParam("Authorization")   String authorizationHeader,
                                          @Valid                          AccountAttributes accountAttributes)
      throws RateLimitExceededException, DeviceLimitExceededException
  {
    try {
      AuthorizationHeader header = AuthorizationHeader.fromFullHeader(authorizationHeader);
      String userLogin              = header.getIdentifier().getUserLogin();
      String password            = header.getPassword();
      
      if (userLogin == null) throw new WebApplicationException(400);

      rateLimiters.getVerifyDeviceLimiter().validate(userLogin);

      Optional<StoredVerificationCode> storedVerificationCode = pendingDevices.getCodeForUserLogin(userLogin);

      if (!storedVerificationCode.isPresent() || !storedVerificationCode.get().isValid(verificationCode)) {
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

      Device device = new Device();
      device.setName(accountAttributes.getName());
      device.setAuthenticationCredentials(new AuthenticationCredentials(password));
      device.setSignalingKey(accountAttributes.getSignalingKey());
      device.setFetchesMessages(accountAttributes.getFetchesMessages());
      device.setId(account.get().getNextDeviceId());
      device.setRegistrationId(accountAttributes.getRegistrationId());
      device.setLastSeen(Util.todayInMillis());
      device.setCreated(System.currentTimeMillis());

      account.get().addDevice(device);
      messages.clear(account.get().getUserLogin(), device.getId());
      accounts.update(account.get());

      pendingDevices.remove(userLogin);

      return new DeviceResponse(device.getId());
    } catch (InvalidAuthorizationHeaderException e) {
      logger.info("Bad Authorization Header", e);
      throw new WebApplicationException(Response.status(401).build());
    }
  }
  
  @Timed
  @PUT
  @Path("/unauthenticated_delivery")
  public void setUnauthenticatedDelivery(@Auth Account account) {
    assert(account.getAuthenticatedDevice().isPresent());
    // Deprecated
  }

  @Timed
  @PUT
  @Path("/capabilities")
  public void setCapabiltities(@Auth Account account, @Valid DeviceCapabilities capabilities) {
    assert(account.getAuthenticatedDevice().isPresent());
    account.getAuthenticatedDevice().get().setCapabilities(capabilities);
    accounts.update(account);
  }

  @VisibleForTesting protected VerificationCode generateVerificationCode() {
    SecureRandom random = new SecureRandom();
    int randomInt       = 100000 + random.nextInt(900000);
    return new VerificationCode(randomInt);
  }
}
