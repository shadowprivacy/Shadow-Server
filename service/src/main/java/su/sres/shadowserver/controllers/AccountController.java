/*
 * Copyright (C) 2013 Open WhisperSystems
 * Modified code copyright 2019 Sophisticated Research
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

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.annotation.Timed;

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
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.codahale.metrics.MetricRegistry.name;
import io.dropwizard.auth.Auth;
import su.sres.shadowserver.auth.AuthenticationCredentials;
import su.sres.shadowserver.auth.AuthorizationHeader;
import su.sres.shadowserver.auth.DisabledPermittedAccount;
import su.sres.shadowserver.auth.InvalidAuthorizationHeaderException;
import su.sres.shadowserver.auth.StoredVerificationCode;
import su.sres.shadowserver.auth.TurnToken;
import su.sres.shadowserver.auth.TurnTokenGenerator;
import su.sres.shadowserver.configuration.LocalParametersConfiguration;
import su.sres.shadowserver.configuration.ServiceConfiguration;
import su.sres.shadowserver.entities.AccountAttributes;
import su.sres.shadowserver.entities.AccountCreationResult;
import su.sres.shadowserver.entities.ApnRegistrationId;
import su.sres.shadowserver.entities.DeviceName;
import su.sres.shadowserver.entities.GcmRegistrationId;
import su.sres.shadowserver.entities.DeprecatedPin;
import su.sres.shadowserver.entities.RegistrationLock;
import su.sres.shadowserver.entities.RegistrationLockFailure;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.providers.CertsProvider;
import su.sres.shadowserver.providers.SystemCerts;
import su.sres.shadowserver.providers.SystemCertsVersion;
// import su.sres.shadowserver.push.APNSender;
// import su.sres.shadowserver.push.ApnMessage;
import su.sres.shadowserver.push.GCMSender;
import su.sres.shadowserver.push.GcmMessage;
import su.sres.shadowserver.recaptcha.RecaptchaClient;
import su.sres.shadowserver.storage.AbusiveHostRule;
import su.sres.shadowserver.storage.AbusiveHostRules;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.MessagesManager;
import su.sres.shadowserver.storage.PaymentAddressList;
import su.sres.shadowserver.storage.PendingAccountsManager;
import su.sres.shadowserver.storage.UsernamesManager;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.Hex;
import su.sres.shadowserver.util.Util;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/accounts")
public class AccountController {

    private final Logger logger = LoggerFactory.getLogger(AccountController.class);
    private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
    private final Meter newUserMeter = metricRegistry.meter(name(AccountController.class, "brand_new_user"));
    private final Meter blockedHostMeter = metricRegistry.meter(name(AccountController.class, "blocked_host"));
    private final Meter filteredHostMeter = metricRegistry.meter(name(AccountController.class, "filtered_host"));
    private final Meter rateLimitedHostMeter = metricRegistry.meter(name(AccountController.class, "rate_limited_host"));
    private final Meter rateLimitedPrefixMeter = metricRegistry
	    .meter(name(AccountController.class, "rate_limited_prefix"));
    private final Meter captchaSuccessMeter = metricRegistry.meter(name(AccountController.class, "captcha_success"));
    private final Meter captchaFailureMeter = metricRegistry.meter(name(AccountController.class, "captcha_failure"));

    private final PendingAccountsManager pendingAccounts;
    private final AccountsManager accounts;
    private final UsernamesManager usernames;
    private final AbusiveHostRules abusiveHostRules;
    private final RateLimiters rateLimiters;

    private final MessagesManager messagesManager;
    private final TurnTokenGenerator turnTokenGenerator;
    private final Map<String, Integer> testDevices;
    private final RecaptchaClient recaptchaClient;
    private final GCMSender gcmSender;
//	  private final APNSender              apnSender;	
    private final LocalParametersConfiguration localParametersConfiguration;
    private final ServiceConfiguration serviceConfiguration;

    public AccountController(PendingAccountsManager pendingAccounts, AccountsManager accounts,
	    UsernamesManager usernames, AbusiveHostRules abusiveHostRules, RateLimiters rateLimiters,
	    MessagesManager messagesManager, TurnTokenGenerator turnTokenGenerator, Map<String, Integer> testDevices,
	    RecaptchaClient recaptchaClient, GCMSender gcmSender,
	    // APNSender apnSender,
	    LocalParametersConfiguration localParametersConfiguration, ServiceConfiguration serviceConfiguration) {
	this.pendingAccounts = pendingAccounts;
	this.accounts = accounts;
	this.usernames = usernames;
	this.abusiveHostRules = abusiveHostRules;
	this.rateLimiters = rateLimiters;

	this.messagesManager = messagesManager;
	this.testDevices = testDevices;
	this.turnTokenGenerator = turnTokenGenerator;
	this.recaptchaClient = recaptchaClient;
	this.gcmSender = gcmSender;
//    this.apnSender          = apnSender;		
	this.localParametersConfiguration = localParametersConfiguration;
	this.serviceConfiguration = serviceConfiguration;
    }

    @Timed
    @GET
    @Path("/{type}/preauth/{token}/{number}")
    public Response getPreAuth(@PathParam("type") String pushType, @PathParam("token") String pushToken,
	    @PathParam("number") String userLogin) {

	if (!"apn".equals(pushType) && !"fcm".equals(pushType)) {
	    return Response.status(400).build();
	}

	if (!Util.isValidUserLogin(userLogin)) {
	    return Response.status(400).build();
	}

	String pushChallenge = generatePushChallenge();
	Optional<StoredVerificationCode> presetVerificationCode = pendingAccounts.getCodeForUserLogin(userLogin);

	if (presetVerificationCode.isPresent()) {

	    presetVerificationCode.get().setPushCode(pushChallenge);

	} else {
	    return Response.status(400).build();
	}

	pendingAccounts.store(userLogin, presetVerificationCode.get());

	if ("fcm".equals(pushType)) {
	    gcmSender.sendMessage(new GcmMessage(pushToken, userLogin, 0, GcmMessage.Type.CHALLENGE,
		    Optional.of(presetVerificationCode.get().getPushCode())));
//    } else if ("apn".equals(pushType)) {
//      apnSender.sendMessage(new ApnMessage(pushToken, number, 0, true, Optional.of(presetVerificationCode.get().getPushCode())));
	} else {
	    throw new AssertionError();
	}

	return Response.ok().build();
    }

    @Timed
    @GET
    @Path("/{transport}/code/{number}")
    public Response createAccount(@PathParam("transport") String transport, @PathParam("number") String userLogin,
	    // @HeaderParam("X-Forwarded-For") String forwardedFor,
	    @HeaderParam("User-Agent") List<String> userAgent,
	    @HeaderParam("Accept-Language") Optional<String> locale, @QueryParam("client") Optional<String> client,
	    @QueryParam("captcha") Optional<String> captcha, @QueryParam("challenge") Optional<String> pushChallenge)

	    throws RateLimitExceededException {

	if (!Util.isValidUserLogin(userLogin)) {
	    logger.info("Invalid user login: " + userLogin);
	    throw new WebApplicationException(Response.status(400).build());
	}

// exclude the need for X-Forwarded-For
// TODO change fillers for real IP address

//    String requester = Arrays.stream(forwardedFor.split(","))
//            .map(String::trim)
//            .reduce((a, b) -> b)
//            .orElseThrow();            

	String forwardedFor = "127.0.0.66";
	String requester = "127.0.0.66";

	Optional<StoredVerificationCode> storedVerificationCode = pendingAccounts.getCodeForUserLogin(userLogin);
	CaptchaRequirement requirement = requiresCaptcha(userLogin, transport, forwardedFor, requester, captcha,
		storedVerificationCode, pushChallenge);

	if (requirement.isCaptchaRequired()) {
	    if (requirement.isAutoBlock() && shouldAutoBlock(requester)) {
		logger.info("Auto-block: " + requester);
		abusiveHostRules.setBlockedHost(requester, "Auto-Block");
	    }

	    return Response.status(402).build();
	}

	switch (transport) {
	case "sms":
	    rateLimiters.getSmsDestinationLimiter().validate(userLogin);
	    break;
	case "voice":
	    rateLimiters.getVoiceDestinationLimiter().validate(userLogin);
	    rateLimiters.getVoiceDestinationDailyLimiter().validate(userLogin);
	    break;
	default:
	    throw new WebApplicationException(Response.status(422).build());
	}

//		metricRegistry.meter(name(AccountController.class, "create", Util.getCountryCode(number))).mark();

	return Response.ok().build();
    }

    @Timed
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/code/{verification_code}")
    public AccountCreationResult verifyAccount(@PathParam("verification_code") String verificationCode,
	    @HeaderParam("Authorization") String authorizationHeader, @HeaderParam("X-Signal-Agent") String userAgent,
	    @QueryParam("transfer") Optional<Boolean> availableForTransfer, @Valid AccountAttributes accountAttributes)
	    throws RateLimitExceededException {
	try {

	    AuthorizationHeader header = AuthorizationHeader.fromFullHeader(authorizationHeader);
	    String userLogin = header.getIdentifier().getUserLogin();
	    String password = header.getPassword();

	    if (userLogin == null) {
		throw new WebApplicationException(400);
	    }

	    rateLimiters.getVerifyLimiter().validate(userLogin);

	    Optional<StoredVerificationCode> storedVerificationCode = pendingAccounts.getCodeForUserLogin(userLogin);

	    if (storedVerificationCode.isEmpty() || !storedVerificationCode.get().isValid(verificationCode)) {
		throw new WebApplicationException(Response.status(403).build());
	    }

	    Optional<Account> existingAccount = accounts.get(userLogin);

	    if (existingAccount.isPresent()
		    && (existingAccount.get().getPin().isPresent()
			    || existingAccount.get().getRegistrationLock().isPresent())
		    && System.currentTimeMillis() - existingAccount.get().getLastSeen() < TimeUnit.DAYS.toMillis(7)) {
		rateLimiters.getVerifyLimiter().clear(userLogin);

		long timeRemaining = TimeUnit.DAYS.toMillis(7)
			- (System.currentTimeMillis() - existingAccount.get().getLastSeen());

		if (Util.isEmpty(accountAttributes.getPin()) && Util.isEmpty(accountAttributes.getRegistrationLock())) {
		    throw new WebApplicationException(
			    Response.status(423).entity(new RegistrationLockFailure(timeRemaining)).build());
		}

		rateLimiters.getPinLimiter().validate(userLogin);

		boolean pinMatches;

		if (existingAccount.get().getRegistrationLock().isPresent()
			&& existingAccount.get().getRegistrationLockSalt().isPresent()) {
		    pinMatches = new AuthenticationCredentials(existingAccount.get().getRegistrationLock().get(),
			    existingAccount.get().getRegistrationLockSalt().get())
				    .verify(accountAttributes.getRegistrationLock());
		} else if (existingAccount.get().getPin().isPresent()) {
		    pinMatches = MessageDigest.isEqual(existingAccount.get().getPin().get().getBytes(),
			    accountAttributes.getPin().getBytes());
		} else {
		    throw new AssertionError("Invalid registration lock state");
		}

		if (!pinMatches) {
		    throw new WebApplicationException(
			    Response.status(423).entity(new RegistrationLockFailure(timeRemaining)).build());
		}

		rateLimiters.getPinLimiter().clear(userLogin);
	    }

	    if (availableForTransfer.orElse(false) && existingAccount.map(Account::isTransferSupported).orElse(false)) {
		throw new WebApplicationException(Response.status(409).build());
	    }

	    if (accounts.getAccountCreationLock() || accounts.getAccountRemovalLock()
		    || accounts.getDirectoryManager().getDirectoryReadLock() || accounts.getDirectoryRestoreLock()) {

		throw new WebApplicationException(
			Response.status(503).header("Retry-After", Integer.valueOf(60)).build());
	    }

	    Account account = createAccount(userLogin, password, userAgent, accountAttributes);

//			metricRegistry.meter(name(AccountController.class, "verify", Util.getCountryCode(number))).mark();

	    return new AccountCreationResult(account.getUuid(),
		    existingAccount.map(Account::isStorageSupported).orElse(false));

	} catch (InvalidAuthorizationHeaderException e) {
	    logger.info("Bad Authorization Header", e);
	    throw new WebApplicationException(Response.status(401).build());
	}
    }

    @Timed
    @GET
    @Path("/turn/")
    @Produces(MediaType.APPLICATION_JSON)
    public TurnToken getTurnToken(@Auth Account account) throws RateLimitExceededException {
	rateLimiters.getTurnLimiter().validate(account.getUserLogin());
	return turnTokenGenerator.generate();
    }

    @Timed
    @GET
    @Path("/config/")
    @Produces(MediaType.APPLICATION_JSON)
    public ServiceConfiguration getServiceConfiguration(@Auth Account account) throws RateLimitExceededException {
	rateLimiters.getConfigLimiter().validate(account.getUserLogin());
	return serviceConfiguration;
    }

    @Timed
    @GET
    @Path("/cert/")
    @Produces(MediaType.APPLICATION_JSON)
    public SystemCerts getCerts(@Auth Account account) throws RateLimitExceededException {
	rateLimiters.getCertLimiter().validate(account.getUserLogin());

	return (new CertsProvider(localParametersConfiguration, serviceConfiguration)).getCerts();
    }

    @Timed
    @GET
    @Path("/certver/")
    @Produces(MediaType.APPLICATION_JSON)
    public SystemCertsVersion getCertsVersion(@Auth Account account) throws RateLimitExceededException {
	rateLimiters.getCertVerLimiter().validate(account.getUserLogin());

	return (new CertsProvider(localParametersConfiguration, serviceConfiguration)).getCertsVersion();
    }

    @Timed
    @GET
    @Path("/license")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getLicenseFile(@Auth Account account) throws RateLimitExceededException {

	String login = account.getUserLogin();

	rateLimiters.getLicenseLimiter().validate(login);

	String filename = login + ".bin";

	try {
	    InputStream is = new FileInputStream(localParametersConfiguration.getLicensePath() + "/" + filename);

	    return Response.ok(is).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
		    .build();
	} catch (FileNotFoundException e) {
	    throw new WebApplicationException(Response.status(404).build());
	}
    }

    @Timed
    @GET
    @Path("/serverlicense")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getServerLicenseFile(@Auth Account account) throws RateLimitExceededException {

	String filename = Constants.serverLicenseFilename;
	String login = account.getUserLogin();

	rateLimiters.getLicenseLimiter().validate(login);

	try {
	    InputStream is = new FileInputStream(localParametersConfiguration.getLicensePath() + "/" + filename);

	    return Response.ok(is).header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").build();
	} catch (FileNotFoundException e) {
	    throw new WebApplicationException(Response.status(404).build());
	}
    }

    @Timed
    @PUT
    @Path("/gcm/")
    @Consumes(MediaType.APPLICATION_JSON)
    public void setGcmRegistrationId(@Auth DisabledPermittedAccount disabledPermittedAccount,
	    @Valid GcmRegistrationId registrationId) {
	Account account = disabledPermittedAccount.getAccount();
	Device device = account.getAuthenticatedDevice().get();
//	    boolean wasAccountEnabled = account.isEnabled();

	if (device.getGcmId() != null && device.getGcmId().equals(registrationId.getGcmRegistrationId())) {
	    return;
	}

	device.setApnId(null);
	device.setVoipApnId(null);
	device.setGcmId(registrationId.getGcmRegistrationId());

	device.setFetchesMessages(false);

	accounts.update(account);
    }

    @Timed
    @DELETE
    @Path("/gcm/")
    public void deleteGcmRegistrationId(@Auth DisabledPermittedAccount disabledPermittedAccount) {
	Account account = disabledPermittedAccount.getAccount();
	Device device = account.getAuthenticatedDevice().get();
	device.setGcmId(null);
	device.setFetchesMessages(false);
	accounts.update(account);
    }

    @Timed
    @PUT
    @Path("/apn/")
    @Consumes(MediaType.APPLICATION_JSON)
    public void setApnRegistrationId(@Auth DisabledPermittedAccount disabledPermittedAccount,
	    @Valid ApnRegistrationId registrationId) {
	Account account = disabledPermittedAccount.getAccount();
	Device device = account.getAuthenticatedDevice().get();

	device.setApnId(registrationId.getApnRegistrationId());
	device.setVoipApnId(registrationId.getVoipRegistrationId());
	device.setGcmId(null);
	device.setFetchesMessages(false);
	accounts.update(account);
    }

    @Timed
    @DELETE
    @Path("/apn/")
    public void deleteApnRegistrationId(@Auth DisabledPermittedAccount disabledPermittedAccount) {
	Account account = disabledPermittedAccount.getAccount();
	Device device = account.getAuthenticatedDevice().get();

	device.setApnId(null);
	device.setFetchesMessages(false);
	accounts.update(account);
    }

    @Timed
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/registration_lock")
    public void setRegistrationLock(@Auth Account account, @Valid RegistrationLock accountLock) {
	AuthenticationCredentials credentials = new AuthenticationCredentials(accountLock.getRegistrationLock());
	account.setRegistrationLock(credentials.getHashedAuthenticationToken());
	account.setRegistrationLockSalt(credentials.getSalt());
	account.setPin(null);

	accounts.update(account);
    }

    @Timed
    @DELETE
    @Path("/registration_lock")
    public void removeRegistrationLock(@Auth Account account) {
	account.setRegistrationLock(null);
	account.setRegistrationLockSalt(null);
	accounts.update(account);
    }

    @Timed
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/pin/")
    public void setPin(@Auth Account account, @Valid DeprecatedPin accountLock) {
	account.setPin(accountLock.getPin());
	account.setRegistrationLock(null);
	account.setRegistrationLockSalt(null);
	accounts.update(account);
    }

    @Timed
    @DELETE
    @Path("/pin/")
    public void removePin(@Auth Account account) {
	account.setPin(null);
	accounts.update(account);
    }

    @Timed
    @PUT
    @Path("/name/")
    public void setName(@Auth DisabledPermittedAccount disabledPermittedAccount, @Valid DeviceName deviceName) {
	Account account = disabledPermittedAccount.getAccount();

	account.getAuthenticatedDevice().get().setName(deviceName.getDeviceName());
	accounts.update(account);
    }

    @Timed
    @DELETE
    @Path("/signaling_key")
    public void removeSignalingKey(@Auth DisabledPermittedAccount disabledPermittedAccount) {
	Account account = disabledPermittedAccount.getAccount();

	account.getAuthenticatedDevice().get().setSignalingKey(null);
	accounts.update(account);
    }

    @Timed
    @PUT
    @Path("/attributes/")
    @Consumes(MediaType.APPLICATION_JSON)
    public void setAccountAttributes(@Auth DisabledPermittedAccount disabledPermittedAccount,
	    @HeaderParam("X-Signal-Agent") String userAgent, @Valid AccountAttributes attributes) {
	Account account = disabledPermittedAccount.getAccount();
	Device device = account.getAuthenticatedDevice().get();

	device.setFetchesMessages(attributes.getFetchesMessages());
	device.setName(attributes.getName());
	device.setLastSeen(Util.todayInMillis());
	device.setCapabilities(attributes.getCapabilities());
	device.setRegistrationId(attributes.getRegistrationId());
	device.setSignalingKey(attributes.getSignalingKey());
	device.setUserAgent(userAgent);

	if (!Util.isEmpty(attributes.getPin())) {
	    account.setPin(attributes.getPin());
	} else if (!Util.isEmpty(attributes.getRegistrationLock())) {
	    AuthenticationCredentials credentials = new AuthenticationCredentials(attributes.getRegistrationLock());
	    account.setRegistrationLock(credentials.getHashedAuthenticationToken());
	    account.setRegistrationLockSalt(credentials.getSalt());
	} else {
	    account.setPin(null);
	    account.setRegistrationLock(null);
	    account.setRegistrationLockSalt(null);
	}

	account.setUnidentifiedAccessKey(attributes.getUnidentifiedAccessKey());
	account.setUnrestrictedUnidentifiedAccess(attributes.isUnrestrictedUnidentifiedAccess());
	account.setPayments(attributes.getPayments());
	account.setDiscoverableByUserLogin(attributes.isDiscoverableByUserLogin());

	accounts.update(account);
    }

    @GET
    @Path("/me")
    @Produces(MediaType.APPLICATION_JSON)
    public AccountCreationResult getMe(@Auth Account account) {
	return whoAmI(account);
    }

    @GET
    @Path("/whoami")
    @Produces(MediaType.APPLICATION_JSON)
    public AccountCreationResult whoAmI(@Auth Account account) {
	return new AccountCreationResult(account.getUuid(), account.isStorageSupported());
    }

    @DELETE
    @Path("/username")
    @Produces(MediaType.APPLICATION_JSON)
    public void deleteUsername(@Auth Account account) {
	usernames.delete(account.getUuid());
    }

    @PUT
    @Path("/username/{username}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response setUsername(@Auth Account account, @PathParam("username") String username)
	    throws RateLimitExceededException {
	rateLimiters.getUsernameSetLimiter().validate(account.getUuid().toString());

	if (username == null || username.isEmpty()) {
	    return Response.status(Response.Status.BAD_REQUEST).build();
	}

	username = username.toLowerCase();

	if (!username.matches("^[a-z_][a-z0-9_]+$")) {
	    return Response.status(Response.Status.BAD_REQUEST).build();
	}

	if (!usernames.put(account.getUuid(), username)) {
	    return Response.status(Response.Status.CONFLICT).build();
	}

	return Response.ok().build();
    }

    @Timed
    @PUT
    @Path("/payments")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public void setPayments(@Auth Account account, @Valid PaymentAddressList payments) {
	account.setPayments(payments.getPayments());
	accounts.update(account);
    }

    private CaptchaRequirement requiresCaptcha(String userLogin, String transport, String forwardedFor,
	    String requester, Optional<String> captchaToken, Optional<StoredVerificationCode> storedVerificationCode,
	    Optional<String> pushChallenge) {
	if (captchaToken.isPresent()) {
	    boolean validToken = recaptchaClient.verify(captchaToken.get(), requester);

	    if (validToken) {
		captchaSuccessMeter.mark();
		return new CaptchaRequirement(false, false);
	    } else {
		captchaFailureMeter.mark();
// captcha off
		return new CaptchaRequirement(false, false);
	    }
	}

	if (pushChallenge.isPresent()) {
	    Optional<String> storedPushChallenge = storedVerificationCode.map(StoredVerificationCode::getPushCode);

	    if (!pushChallenge.get().equals(storedPushChallenge.orElse(null))) {
// captcha off
//				return new CaptchaRequirement(true, false);
		return new CaptchaRequirement(false, false);
	    }
	}

	List<AbusiveHostRule> abuseRules = abusiveHostRules.getAbusiveHostRulesFor(requester);

	for (AbusiveHostRule abuseRule : abuseRules) {
	    if (abuseRule.isBlocked()) {
		logger.info(
			"Blocked host: " + transport + ", " + userLogin + ", " + requester + " (" + forwardedFor + ")");
		blockedHostMeter.mark();
// captcha off
		// return new CaptchaRequirement(true, false);
		return new CaptchaRequirement(false, false);
	    }

// should always be empty, since we use E.164 numbers no more			
	    /*
	     * if (!abuseRule.getRegions().isEmpty()) { if
	     * (abuseRule.getRegions().stream().noneMatch(number::startsWith)) {
	     * logger.info("Restricted host: " + transport + ", " + number + ", " +
	     * requester + " (" + forwardedFor + ")"); filteredHostMeter.mark(); // captcha
	     * off // return new CaptchaRequirement(true, false); return new
	     * CaptchaRequirement(false, false); } }
	     */
	}

	try {
	    rateLimiters.getSmsVoiceIpLimiter().validate(requester);
	} catch (RateLimitExceededException e) {
	    logger.info("Rate limited exceeded: " + transport + ", " + userLogin + ", " + requester + " ("
		    + forwardedFor + ")");
	    rateLimitedHostMeter.mark();
	    // captcha off
	    // return new CaptchaRequirement(true, true);
	    return new CaptchaRequirement(false, true);
	}

	return new CaptchaRequirement(false, false);
    }

    @Timed
    @DELETE
    @Path("/me")
    public void deleteAccount(@Auth Account account) {
	accounts.delete(Stream.of(account).collect(Collectors.toCollection(HashSet::new)));
    }

    private boolean shouldAutoBlock(String requester) {
	try {
	    rateLimiters.getAutoBlockLimiter().validate(requester);
	} catch (RateLimitExceededException e) {
	    return true;
	}

	return false;
    }

    private Account createAccount(String userLogin, String password, String userAgent, AccountAttributes accountAttributes) {
	Optional<Account> maybeExistingAccount = accounts.get(userLogin);

	Device device = new Device();
	device.setId(Device.MASTER_ID);
	device.setAuthenticationCredentials(new AuthenticationCredentials(password));
	device.setSignalingKey(accountAttributes.getSignalingKey());
	device.setFetchesMessages(accountAttributes.getFetchesMessages());
	device.setRegistrationId(accountAttributes.getRegistrationId());
	device.setName(accountAttributes.getName());
	device.setCapabilities(accountAttributes.getCapabilities());
	device.setCreated(System.currentTimeMillis());
	device.setLastSeen(Util.todayInMillis());
	device.setUserAgent(userAgent);

	Account account = new Account();
	account.setUserLogin(userLogin);
	account.setUuid(UUID.randomUUID());

	account.addDevice(device);
	account.setPin(accountAttributes.getPin());

	account.setUnidentifiedAccessKey(accountAttributes.getUnidentifiedAccessKey());
	account.setUnrestrictedUnidentifiedAccess(accountAttributes.isUnrestrictedUnidentifiedAccess());
	account.setPayments(accountAttributes.getPayments());
	account.setDiscoverableByUserLogin(accountAttributes.isDiscoverableByUserLogin());

	if (accounts.create(account)) {
	    newUserMeter.mark();
	}

	messagesManager.clear(userLogin, maybeExistingAccount.map(Account::getUuid).orElse(null));
	pendingAccounts.remove(userLogin);

	return account;
    }

    private String generatePushChallenge() {
	SecureRandom random = new SecureRandom();
	byte[] challenge = new byte[16];
	random.nextBytes(challenge);

	return Hex.toStringCondensed(challenge);
    }

    private static class CaptchaRequirement {
	private final boolean captchaRequired;
	private final boolean autoBlock;

	private CaptchaRequirement(boolean captchaRequired, boolean autoBlock) {
	    this.captchaRequired = captchaRequired;
	    this.autoBlock = autoBlock;
	}

	boolean isCaptchaRequired() {
	    return captchaRequired;
	}

	boolean isAutoBlock() {
	    return autoBlock;
	}
    }
}
