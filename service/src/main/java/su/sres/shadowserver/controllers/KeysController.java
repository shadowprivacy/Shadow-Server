/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import io.dropwizard.auth.Auth;
import su.sres.shadowserver.entities.PreKey;
import su.sres.shadowserver.entities.PreKeyCount;
import su.sres.shadowserver.entities.PreKeyResponse;
import su.sres.shadowserver.entities.PreKeyResponseItem;
import su.sres.shadowserver.entities.PreKeyState;
import su.sres.shadowserver.entities.SignedPreKey;
// excluded federation, reserved for future use
// import su.sres.shadowserver.federation.FederatedClientManager;
// import su.sres.shadowserver.federation.NoSuchPeerException;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.KeyRecord;
import su.sres.shadowserver.storage.Keys;
import su.sres.shadowserver.storage.KeysScyllaDb;
import su.sres.shadowserver.storage.PreKeyStore;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v2/keys")
public class KeysController {

    private final RateLimiters rateLimiters;
    private final Keys keys;
    private final KeysScyllaDb keysScyllaDb;
    private final AccountsManager accounts;
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
    public KeysController(RateLimiters rateLimiters, Keys keys, KeysScyllaDb keysScyllaDb, AccountsManager accounts) {
	this.rateLimiters = rateLimiters;
	this.keys = keys;
	this.keysScyllaDb = keysScyllaDb;
	this.accounts = accounts;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public PreKeyCount getStatus(@Auth Account account) {
	int count = getPreKeyStoreForProducer(account).getCount(account, account.getAuthenticatedDevice().get().getId());

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

	getPreKeyStoreForProducer(account).store(account, device.getId(), preKeys.getPreKeys());
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
    public Optional<PreKeyResponse> getDeviceKeys(@Auth Optional<Account> account,
	    @HeaderParam(OptionalAccess.UNIDENTIFIED) Optional<Anonymous> accessKey,
	    @PathParam("identifier") AmbiguousIdentifier targetName, @PathParam("device_id") String deviceId)
	    throws RateLimitExceededException {
	if (!account.isPresent() && !accessKey.isPresent()) {
	    throw new WebApplicationException(Response.Status.UNAUTHORIZED);
	}

	Optional<Account> target = accounts.get(targetName);
	OptionalAccess.verify(account, accessKey, target, deviceId);

	assert (target.isPresent());

	if (account.isPresent()) {
	    rateLimiters.getPreKeysLimiter().validate(account.get().getUserLogin() + "." + account.get().getAuthenticatedDevice().get().getId() + "__" + target.get().getUserLogin() + "." + deviceId);
	}

	List<KeyRecord> targetKeys = getLocalKeys(target.get(), deviceId);
	List<PreKeyResponseItem> devices = new LinkedList<>();

	for (Device device : target.get().getDevices()) {
	    if (device.isEnabled() && (deviceId.equals("*") || device.getId() == Long.parseLong(deviceId))) {
		SignedPreKey signedPreKey = device.getSignedPreKey();
		PreKey preKey = null;

		for (KeyRecord keyRecord : targetKeys) {
		    if (keyRecord.getDeviceId() == device.getId()) {
			preKey = new PreKey(keyRecord.getKeyId(), keyRecord.getPublicKey());
		    }
		}

		if (signedPreKey != null || preKey != null) {
		    devices.add(
			    new PreKeyResponseItem(device.getId(), device.getRegistrationId(), signedPreKey, preKey));
		}
	    }
	}

	if (devices.isEmpty())
	    return Optional.empty();
	else
	    return Optional.of(new PreKeyResponse(target.get().getIdentityKey(), devices));
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

    private List<KeyRecord> getLocalKeys(Account destination, String deviceIdSelector) {
// excluded federation (?), reserved for future use 
	// throws NoSuchUserException

	try {
	    if (deviceIdSelector.equals("*")) {
		return getPreKeyStoreForConsumer(destination).take(destination);
	    }

	    long deviceId = Long.parseLong(deviceIdSelector);

	    return getPreKeyStoreForConsumer(destination).take(destination, deviceId);
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

    private PreKeyStore getPreKeyStoreForProducer(final Account account) {
	return keysScyllaDb;
    }

    private PreKeyStore getPreKeyStoreForConsumer(final Account account) {
	return keysScyllaDb;
    }
}
