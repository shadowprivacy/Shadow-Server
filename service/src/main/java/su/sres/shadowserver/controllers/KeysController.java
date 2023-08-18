/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.annotation.Timed;

import su.sres.shadowserver.auth.AmbiguousIdentifier;
import su.sres.shadowserver.auth.Anonymous;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.OptionalAccess;

import javax.validation.Valid;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
// import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;
import su.sres.shadowserver.entities.PreKey;
import su.sres.shadowserver.entities.PreKeyCount;
import su.sres.shadowserver.entities.PreKeyResponse;
import su.sres.shadowserver.entities.PreKeyResponseItem;
import su.sres.shadowserver.entities.PreKeyState;
import su.sres.shadowserver.entities.SignedPreKey;
import su.sres.shadowserver.limits.PreKeyRateLimiter;
import su.sres.shadowserver.limits.RateLimitChallengeException;
import su.sres.shadowserver.limits.RateLimitChallengeManager;
// excluded federation, reserved for future use
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.NoSuchPeerException;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.KeysScyllaDb;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v2/keys")
public class KeysController {

  private final RateLimiters rateLimiters;
  private final KeysScyllaDb keysScyllaDb;
  private final AccountsManager accounts;
  
  private final PreKeyRateLimiter preKeyRateLimiter;

  private final RateLimitChallengeManager rateLimitChallengeManager;
  
  private static final String RATE_LIMITED_GET_PREKEYS_COUNTER_NAME = name(KeysController.class, "rateLimitedGetPreKeys");
      
  // excluded federation, reserved for future use
  // private final FederatedClientManager federatedClientManager;

  /*
   * excluded federation, reserved for future use
   * 
   * 
   * public KeysController(RateLimiters rateLimiters, Keys keys, AccountsManager
   * accounts, FederatedClientManager federatedClientManager) { this.rateLimiters
   * = rateLimiters; this.keys = keys; this.accounts = accounts;
   * this.federatedClientManager = federatedClientManager; }
   */
  public KeysController(RateLimiters rateLimiters, KeysScyllaDb keysScyllaDb, AccountsManager accounts, PreKeyRateLimiter preKeyRateLimiter,      
      RateLimitChallengeManager rateLimitChallengeManager) {
    this.rateLimiters = rateLimiters;
    this.keysScyllaDb = keysScyllaDb;
    this.accounts = accounts;
    this.preKeyRateLimiter = preKeyRateLimiter;
    
    this.rateLimitChallengeManager = rateLimitChallengeManager;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public PreKeyCount getStatus(@Auth Account account) {
    int count = keysScyllaDb.getCount(account, account.getAuthenticatedDevice().get().getId());

    if (count > 0) {
      count = count - 1;
    }

    return new PreKeyCount(count);
  }

  @Timed
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  public void setKeys(@Auth DisabledPermittedAccount disabledPermittedAccount, @Valid PreKeyState preKeys) {

    Account account = disabledPermittedAccount.getAccount();
    Device device = account.getAuthenticatedDevice().get();
    boolean updateAccount = false;

    if (!preKeys.getSignedPreKey().equals(device.getSignedPreKey())) {

      device.setSignedPreKey(preKeys.getSignedPreKey());
      updateAccount = true;
    }

    if (!preKeys.getIdentityKey().equals(account.getIdentityKey())) {

      account.setIdentityKey(preKeys.getIdentityKey());
      updateAccount = true;
    }

    if (updateAccount) {
      accounts.update(account);
    }

    keysScyllaDb.store(account, device.getId(), preKeys.getPreKeys());
  }

  /*
   * excluded federation, reserved for future use
   * 
   * 
   * public Optional<PreKeyResponse> getDeviceKeys(@Auth Account account,
   * 
   * @PathParam("number") String number,
   * 
   * @PathParam("device_id") String deviceId,
   * 
   * @QueryParam("relay") Optional<String> relay) throws
   * RateLimitExceededException { try { if (relay.isPresent()) { return
   * federatedClientManager.getClient(relay.get()).getKeysV2(number, deviceId); }
   * 
   * Account target = getAccount(number, deviceId);
   * 
   * if (account.isRateLimited()) {
   * rateLimiters.getPreKeysLimiter().validate(account.getNumber() + "__" + number
   * + "." + deviceId); }
   * 
   * Optional<List<KeyRecord>> targetKeys = getLocalKeys(target, deviceId);
   * List<PreKeyResponseItem> devices = new LinkedList<>();
   * 
   * for (Device device : target.getDevices()) { if (device.isActive() &&
   * (deviceId.equals("*") || device.getId() == Long.parseLong(deviceId))) {
   * SignedPreKey signedPreKey = device.getSignedPreKey(); PreKey preKey = null;
   * 
   * if (targetKeys.isPresent()) { for (KeyRecord keyRecord : targetKeys.get()) {
   * if (!keyRecord.isLastResort() && keyRecord.getDeviceId() == device.getId()) {
   * preKey = new PreKey(keyRecord.getKeyId(), keyRecord.getPublicKey()); } } }
   * 
   * if (signedPreKey != null || preKey != null) { devices.add(new
   * PreKeyResponseItem(device.getId(), device.getRegistrationId(), signedPreKey,
   * preKey)); } } }
   * 
   * if (devices.isEmpty()) return Optional.absent(); else return Optional.of(new
   * PreKeyResponse(target.getIdentityKey(), devices)); } catch
   * (NoSuchPeerException | NoSuchUserException e) { throw new
   * WebApplicationException(Response.status(404).build()); } }
   * 
   */

  @Timed
  @GET
  @Path("/{identifier}/{device_id}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getDeviceKeys(@Auth Optional<Account> account,
      @HeaderParam(OptionalAccess.UNIDENTIFIED) Optional<Anonymous> accessKey,
      @PathParam("identifier") AmbiguousIdentifier targetName, @PathParam("device_id") String deviceId, @HeaderParam("User-Agent")                String userAgent)
          throws RateLimitExceededException, RateLimitChallengeException {      
    if (!account.isPresent() && !accessKey.isPresent()) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    Optional<Account> target = accounts.get(targetName);
    OptionalAccess.verify(account, accessKey, target, deviceId);

    assert (target.isPresent());
    
    if (account.isPresent()) {
      rateLimiters.getPreKeysLimiter().validate(account.get().getUserLogin() + "." + account.get().getAuthenticatedDevice().get().getId() +  "__" + target.get().getUserLogin() + "." + deviceId);

      try {
        preKeyRateLimiter.validate(account.get());
      } catch (RateLimitExceededException e) {

        final boolean enforceLimit = rateLimitChallengeManager.shouldIssueRateLimitChallenge(userAgent);

        Metrics.counter(RATE_LIMITED_GET_PREKEYS_COUNTER_NAME,            
            "enforced", String.valueOf(enforceLimit))
            .increment();

        if (enforceLimit) {
          throw new RateLimitChallengeException(account.get(), e.getRetryDuration());
        }
      }
    }
    
    Map<Long, PreKey> preKeysByDeviceId = getLocalKeys(target.get(), deviceId);
    List<PreKeyResponseItem> responseItems = new LinkedList<>();

    for (Device device : target.get().getDevices()) {
      if (device.isEnabled() && (deviceId.equals("*") || device.getId() == Long.parseLong(deviceId))) {
        SignedPreKey signedPreKey = device.getSignedPreKey();
        PreKey preKey = preKeysByDeviceId.get(device.getId());

        if (signedPreKey != null || preKey != null) {
          responseItems.add(new PreKeyResponseItem(device.getId(), device.getRegistrationId(), signedPreKey, preKey));
        }
      }
    }

    if (responseItems.isEmpty()) return Response.status(404).build();
    else                         return Response.ok().entity(new PreKeyResponse(target.get().getIdentityKey(), responseItems)).build();
  }

  @Timed
  @PUT
  @Path("/signed")
  @Consumes(MediaType.APPLICATION_JSON)
  public void setSignedKey(@Auth Account account, @Valid SignedPreKey signedPreKey) {
    Device device = account.getAuthenticatedDevice().get();

    device.setSignedPreKey(signedPreKey);
    accounts.update(account);
  }

  @Timed
  @GET
  @Path("/signed")
  @Produces(MediaType.APPLICATION_JSON)
  public Optional<SignedPreKey> getSignedKey(@Auth Account account) {
    Device device = account.getAuthenticatedDevice().get();
    SignedPreKey signedPreKey = device.getSignedPreKey();

    if (signedPreKey != null)
      return Optional.of(signedPreKey);
    else
      return Optional.empty();
  }

  private Map<Long, PreKey> getLocalKeys(Account destination, String deviceIdSelector) {
// excluded federation (?), reserved for future use 
    // throws NoSuchUserException

    try {
      if (deviceIdSelector.equals("*")) {
        return keysScyllaDb.take(destination);
      }

      long deviceId = Long.parseLong(deviceIdSelector);

      return keysScyllaDb.take(destination, deviceId)
          .map(preKey -> Map.of(deviceId, preKey))
          .orElse(Collections.emptyMap());
    } catch (NumberFormatException e) {
      throw new WebApplicationException(Response.status(422).build());
    }
  }

  /*
   * excluded federation (?), reserved for future use
   *
   * 
   * private Account getAccount(String number, String deviceSelector) throws
   * NoSuchUserException { try { Optional<Account> account = accounts.get(number);
   * 
   * if (!account.isPresent() || !account.get().isActive()) { throw new
   * NoSuchUserException("No active account"); }
   * 
   * if (!deviceSelector.equals("*")) { long deviceId =
   * Long.parseLong(deviceSelector);
   * 
   * Optional<Device> targetDevice = account.get().getDevice(deviceId);
   * 
   * if (!targetDevice.isPresent() || !targetDevice.get().isActive()) { throw new
   * NoSuchUserException("No active device"); } }
   * 
   * return account.get(); } catch (NumberFormatException e) { throw new
   * WebApplicationException(Response.status(422).build()); } }
   */

}
