/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.annotation.Timed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.codahale.metrics.MetricRegistry.name;
import io.dropwizard.auth.Auth;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.BasicAuthorizationHeader;
import su.sres.shadowserver.auth.DisabledPermittedAuthenticatedAccount;
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
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.metrics.UserAgentTagUtil;
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
import su.sres.shadowserver.storage.StoredVerificationCodeManager;
import su.sres.shadowserver.storage.UsernamesManager;
import su.sres.shadowserver.util.Constants;
import su.sres.shadowserver.util.ForwardedIpUtil;
import su.sres.shadowserver.util.Hex;
import su.sres.shadowserver.util.Util;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Path("/v1/accounts")
public class AccountController {

  private final Logger logger = LoggerFactory.getLogger(AccountController.class);
  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter blockedHostMeter = metricRegistry.meter(name(AccountController.class, "blocked_host"));
  private final Meter rateLimitedHostMeter = metricRegistry.meter(name(AccountController.class, "rate_limited_host"));
  private final Meter captchaRequiredMeter = metricRegistry.meter(name(AccountController.class, "captcha_required"));
  private final Meter captchaSuccessMeter = metricRegistry.meter(name(AccountController.class, "captcha_success"));
  private final Meter captchaFailureMeter = metricRegistry.meter(name(AccountController.class, "captcha_failure"));

  private static final String PUSH_CHALLENGE_COUNTER_NAME = name(AccountController.class, "pushChallenge");
  private static final String ACCOUNT_CREATE_COUNTER_NAME = name(AccountController.class, "create");
  private static final String ACCOUNT_VERIFY_COUNTER_NAME = name(AccountController.class, "verify");

  private static final String CHALLENGE_PRESENT_TAG_NAME = "present";
  private static final String CHALLENGE_MATCH_TAG_NAME = "matches";

  private static final String VERFICATION_TRANSPORT_TAG_NAME = "transport";

  private final StoredVerificationCodeManager pendingAccounts;
  private final AccountsManager accounts;
  private final UsernamesManager usernames;
  private final AbusiveHostRules abusiveHostRules;
  private final RateLimiters rateLimiters;
  private final TurnTokenGenerator turnTokenGenerator;
  private final Map<String, Integer> testDevices;
  private final RecaptchaClient recaptchaClient;
  private final GCMSender gcmSender;
//	  private final APNSender              apnSender;	
  private final LocalParametersConfiguration localParametersConfiguration;
  private final ServiceConfiguration serviceConfiguration;

  public AccountController(StoredVerificationCodeManager pendingAccounts, AccountsManager accounts,
      UsernamesManager usernames, AbusiveHostRules abusiveHostRules, RateLimiters rateLimiters,
      TurnTokenGenerator turnTokenGenerator, Map<String, Integer> testDevices,
      RecaptchaClient recaptchaClient, GCMSender gcmSender,
      // APNSender apnSender,
      LocalParametersConfiguration localParametersConfiguration, ServiceConfiguration serviceConfiguration) {
    this.pendingAccounts = pendingAccounts;
    this.accounts = accounts;
    this.usernames = usernames;
    this.abusiveHostRules = abusiveHostRules;
    this.rateLimiters = rateLimiters;
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
      @PathParam("number") String userLogin,
      @QueryParam("voip") Optional<Boolean> useVoip) {

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
      gcmSender.sendMessage(new GcmMessage(pushToken, null, 0, GcmMessage.Type.CHALLENGE,
          Optional.of(presetVerificationCode.get().getPushCode())));
//    } else if ("apn".equals(pushType)) {
//      apnSender.sendMessage(new ApnMessage(pushToken, null, 0, useVoip.orElse(true), ApnMessage.Type.CHALLENGE, Optional.of(presetVerificationCode.get().getPushCode())));
    } else {
      throw new AssertionError();
    }

    return Response.ok().build();
  }

  @Timed
  @GET
  @Path("/{transport}/code/{number}")
  public Response createAccount(@PathParam("transport") String transport, @PathParam("number") String userLogin,
      @HeaderParam("X-Forwarded-For") Optional<String> forwardedFor,
      @HeaderParam("User-Agent") String userAgent,
      @QueryParam("client") Optional<String> client,
      @QueryParam("captcha") Optional<String> captcha,
      @QueryParam("challenge") Optional<String> pushChallenge,
      @Context HttpServletRequest httpServletRequest) throws RateLimitExceededException, RetryLaterException {

    String sourceHost;

    if (!Util.isValidUserLogin(userLogin)) {
      logger.info("Invalid user login: " + userLogin);
      throw new WebApplicationException(Response.status(400).build());
    }

    sourceHost = forwardedFor.isPresent() ? ForwardedIpUtil.getMostRecentProxy(forwardedFor.get()).orElseThrow() : httpServletRequest.getRemoteAddr();

    Optional<StoredVerificationCode> storedVerificationCode = pendingAccounts.getCodeForUserLogin(userLogin);
    CaptchaRequirement requirement = requiresCaptcha(userLogin, transport, forwardedFor, sourceHost, captcha, storedVerificationCode, pushChallenge);

    if (requirement.isCaptchaRequired()) {
      captchaRequiredMeter.mark();
      if (requirement.isAutoBlock() && shouldAutoBlock(sourceHost)) {
        logger.info("Auto-block: {}", sourceHost);
        abusiveHostRules.setBlockedHost(sourceHost, "Auto-Block");
      }

      return Response.status(402).build();
    }

    try {
      switch (transport) {
      case "sms":
        rateLimiters.getSmsDestinationLimiter().validate(userLogin);
        break;
      default:
        throw new WebApplicationException(Response.status(422).build());
      }
    } catch (RateLimitExceededException e) {
      if (!e.getRetryDuration().isNegative()) {
        throw new RetryLaterException(e);
      } else {
        throw e;
      }
    }

    {
      final List<Tag> tags = new ArrayList<>();

      tags.add(Tag.of(VERFICATION_TRANSPORT_TAG_NAME, transport));
      tags.add(UserAgentTagUtil.getPlatformTag(userAgent));

      Metrics.counter(ACCOUNT_CREATE_COUNTER_NAME, tags).increment();
    }

    return Response.ok().build();
  }

  @Timed
  @PUT
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/code/{verification_code}")
  public AccountCreationResult verifyAccount(@PathParam("verification_code") String verificationCode,
      @HeaderParam("Authorization") BasicAuthorizationHeader authorizationHeader,
      @HeaderParam("X-Signal-Agent") String signalAgent,
      @HeaderParam("User-Agent") String userAgent,
      @QueryParam("transfer") Optional<Boolean> availableForTransfer, @Valid AccountAttributes accountAttributes)
      throws RateLimitExceededException {

    String userLogin = authorizationHeader.getUsername();
    String password = authorizationHeader.getPassword();

    rateLimiters.getVerifyLimiter().validate(userLogin);

    Optional<StoredVerificationCode> storedVerificationCode = pendingAccounts.getCodeForUserLogin(userLogin);

    if (storedVerificationCode.isEmpty() || !storedVerificationCode.get().isValid(verificationCode, localParametersConfiguration.getVerificationCodeLifetime())) {
      throw new WebApplicationException(Response.status(403).build());
    }

    Optional<Account> existingAccount = accounts.get(userLogin);

    // TODO: remove this after soft-deletion is abandoned on the client side
    if (existingAccount.isPresent() && System.currentTimeMillis() - existingAccount.get().getLastSeen() < TimeUnit.DAYS.toMillis(7)) {
      rateLimiters.getVerifyLimiter().clear(userLogin);
    }

    if (availableForTransfer.orElse(false) && existingAccount.map(Account::isTransferSupported).orElse(false)) {
      throw new WebApplicationException(Response.status(409).build());
    }

    if (accounts.getAccountCreationLock() || accounts.getAccountRemovalLock()
        || accounts.getDirectoryManager().getDirectoryReadLock() || accounts.getDirectoryRestoreLock()) {

      throw new WebApplicationException(
          Response.status(503).header("Retry-After", Integer.valueOf(60)).build());
    }

    Account account = accounts.create(userLogin, password, signalAgent, accountAttributes);

    {
      final List<Tag> tags = new ArrayList<>();
      tags.add(UserAgentTagUtil.getPlatformTag(userAgent));

      Metrics.counter(ACCOUNT_VERIFY_COUNTER_NAME, tags).increment();
    }

    return new AccountCreationResult(account.getUuid(), account.getUserLogin(), existingAccount.map(Account::isStorageSupported).orElse(false));
  }

  @Timed
  @GET
  @Path("/turn/")
  @Produces(MediaType.APPLICATION_JSON)
  public TurnToken getTurnToken(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {
    rateLimiters.getTurnLimiter().validate(auth.getAccount().getUuid());
    return turnTokenGenerator.generate();
  }

  @Timed
  @GET
  @Path("/config/")
  @Produces(MediaType.APPLICATION_JSON)
  public ServiceConfiguration getServiceConfiguration(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {
    rateLimiters.getConfigLimiter().validate(auth.getAccount().getUuid());
    return serviceConfiguration;
  }

  @Timed
  @GET
  @Path("/cert/")
  @Produces(MediaType.APPLICATION_JSON)
  public SystemCerts getCerts(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {
    rateLimiters.getCertLimiter().validate(auth.getAccount().getUuid());

    return (new CertsProvider(localParametersConfiguration, serviceConfiguration)).getCerts();
  }

  @Timed
  @GET
  @Path("/certver/")
  @Produces(MediaType.APPLICATION_JSON)
  public SystemCertsVersion getCertsVersion(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {
    rateLimiters.getCertVerLimiter().validate(auth.getAccount().getUuid());

    return (new CertsProvider(localParametersConfiguration, serviceConfiguration)).getCertsVersion();
  }

  @Timed
  @GET
  @Path("/license")
  @Produces(MediaType.APPLICATION_OCTET_STREAM)
  public Response getLicenseFile(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {

    rateLimiters.getLicenseLimiter().validate(auth.getAccount().getUuid());

    String filename = auth.getAccount().getUserLogin() + ".bin";

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
  public Response getServerLicenseFile(@Auth AuthenticatedAccount auth) throws RateLimitExceededException {

    String filename = Constants.serverLicenseFilename;

    rateLimiters.getLicenseLimiter().validate(auth.getAccount().getUuid());

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
  public void setGcmRegistrationId(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth,
      @Valid GcmRegistrationId registrationId) {
    Account account = disabledPermittedAuth.getAccount();
    Device device = disabledPermittedAuth.getAuthenticatedDevice();

    if (device.getGcmId() != null && device.getGcmId().equals(registrationId.getGcmRegistrationId())) {
      return;
    }

    accounts.updateDevice(account, device.getId(), d -> {
      d.setApnId(null);
      d.setVoipApnId(null);
      d.setGcmId(registrationId.getGcmRegistrationId());
      d.setFetchesMessages(false);
    });
  }

  @Timed
  @DELETE
  @Path("/gcm/")
  public void deleteGcmRegistrationId(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth) {
    Account account = disabledPermittedAuth.getAccount();
    Device device = disabledPermittedAuth.getAuthenticatedDevice();

    String userLogin = account.getUserLogin();
    boolean noApnId = account.getMasterDevice().get().getApnId() == null;

    accounts.updateDevice(account, device.getId(), d -> {
      d.setGcmId(null);
      d.setFetchesMessages(false);
      d.setUserAgent("OWA");
    });

    if (noApnId)
      accounts.deleteFromDirectory(userLogin);
  }

  @Timed
  @PUT
  @Path("/apn/")
  @Consumes(MediaType.APPLICATION_JSON)
  public void setApnRegistrationId(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth,
      @Valid ApnRegistrationId registrationId) {
    Account account = disabledPermittedAuth.getAccount();
    Device device = disabledPermittedAuth.getAuthenticatedDevice();

    accounts.updateDevice(account, device.getId(), d -> {
      d.setApnId(registrationId.getApnRegistrationId());
      d.setVoipApnId(registrationId.getVoipRegistrationId());
      d.setGcmId(null);
      d.setFetchesMessages(false);
    });
  }

  @Timed
  @DELETE
  @Path("/apn/")
  public void deleteApnRegistrationId(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth) {
    Account account = disabledPermittedAuth.getAccount();
    Device device = disabledPermittedAuth.getAuthenticatedDevice();

    String userLogin = account.getUserLogin();
    boolean noGcmId = account.getMasterDevice().get().getGcmId() == null;

    accounts.updateDevice(account, device.getId(), d -> {
      d.setApnId(null);
      d.setFetchesMessages(false);
      if (d.getId() == 1) {
        d.setUserAgent("OWI");
      } else {
        d.setUserAgent("OWP");
      }
    });

    if (noGcmId)
      accounts.deleteFromDirectory(userLogin);
  }

  @Timed
  @PUT
  @Path("/name/")
  public void setName(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth, @Valid DeviceName deviceName) {
    Account account = disabledPermittedAuth.getAccount();
    Device device = disabledPermittedAuth.getAuthenticatedDevice();

    accounts.updateDevice(account, device.getId(), d -> d.setName(deviceName.getDeviceName()));
  }

  @Timed
  @DELETE
  @Path("/signaling_key")
  public void removeSignalingKey(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth) {
  }

  @Timed
  @PUT
  @Path("/attributes/")
  @Consumes(MediaType.APPLICATION_JSON)
  public void setAccountAttributes(@Auth DisabledPermittedAuthenticatedAccount disabledPermittedAuth,
      @HeaderParam("X-Signal-Agent") String userAgent,
      @Valid AccountAttributes attributes) {
    Account account = disabledPermittedAuth.getAccount();
    long deviceId = disabledPermittedAuth.getAuthenticatedDevice().getId();

    accounts.update(account, a -> {
      a.getDevice(deviceId).ifPresent(d -> {
        d.setFetchesMessages(attributes.getFetchesMessages());
        d.setName(attributes.getName());
        d.setLastSeen(Util.todayInMillis());
        d.setCapabilities(attributes.getCapabilities());
        d.setRegistrationId(attributes.getRegistrationId());
        d.setUserAgent(userAgent);
      });

      a.setUnidentifiedAccessKey(attributes.getUnidentifiedAccessKey());
      a.setUnrestrictedUnidentifiedAccess(attributes.isUnrestrictedUnidentifiedAccess());
      a.setDiscoverableByUserLogin(attributes.isDiscoverableByUserLogin());
    });
  }

  @GET
  @Path("/me")
  @Produces(MediaType.APPLICATION_JSON)
  public AccountCreationResult getMe(@Auth AuthenticatedAccount auth) {
    return whoAmI(auth);
  }

  @GET
  @Path("/whoami")
  @Produces(MediaType.APPLICATION_JSON)
  public AccountCreationResult whoAmI(@Auth AuthenticatedAccount auth) {
    return new AccountCreationResult(auth.getAccount().getUuid(), auth.getAccount().getUserLogin(), auth.getAccount().isStorageSupported());
  }

  @DELETE
  @Path("/username")
  @Produces(MediaType.APPLICATION_JSON)
  public void deleteUsername(@Auth AuthenticatedAccount auth) {
    usernames.delete(auth.getAccount().getUuid());
  }

  @PUT
  @Path("/username/{username}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response setUsername(@Auth AuthenticatedAccount auth, @PathParam("username") String username)
      throws RateLimitExceededException {
    rateLimiters.getUsernameSetLimiter().validate(auth.getAccount().getUuid());

    if (username == null || username.isEmpty()) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }

    username = username.toLowerCase();

    if (!username.matches("^[a-z_][a-z0-9_]+$")) {
      return Response.status(Response.Status.BAD_REQUEST).build();
    }

    if (!usernames.put(auth.getAccount().getUuid(), username)) {
      return Response.status(Response.Status.CONFLICT).build();
    }

    return Response.ok().build();
  }

  private CaptchaRequirement requiresCaptcha(String userLogin, String transport, Optional<String> forwardedFor,
      String sourceHost, Optional<String> captchaToken, Optional<StoredVerificationCode> storedVerificationCode,
      Optional<String> pushChallenge) {

    // this should be skipped, since the captcha token is absent; in case it's
    // somehow present, the guy is rejected with 402
    if (captchaToken.isPresent()) {
      boolean validToken = recaptchaClient.verify(captchaToken.get(), sourceHost);

      if (validToken) {
        captchaSuccessMeter.mark();
        return new CaptchaRequirement(false, false);
      } else {
        captchaFailureMeter.mark();
        return new CaptchaRequirement(true, false);
      }
    }

    {
      final List<Tag> tags = new ArrayList<>();

      try {
        if (pushChallenge.isPresent()) {
          tags.add(Tag.of(CHALLENGE_PRESENT_TAG_NAME, "true"));

          Optional<String> storedPushChallenge = storedVerificationCode.map(StoredVerificationCode::getPushCode);

          if (!pushChallenge.get().equals(storedPushChallenge.orElse(null))) {
            tags.add(Tag.of(CHALLENGE_MATCH_TAG_NAME, "false"));
            return new CaptchaRequirement(true, false);
          } else {
            tags.add(Tag.of(CHALLENGE_MATCH_TAG_NAME, "true"));
          }
        } else {
          tags.add(Tag.of(CHALLENGE_PRESENT_TAG_NAME, "false"));

          // captcha off, proceed to check for abuse
          // return new CaptchaRequirement(false, false);
        }
      } finally {
        Metrics.counter(PUSH_CHALLENGE_COUNTER_NAME, tags).increment();
      }
    }

    List<AbusiveHostRule> abuseRules = abusiveHostRules.getAbusiveHostRulesFor(sourceHost);

    for (AbusiveHostRule abuseRule : abuseRules) {
      if (abuseRule.isBlocked()) {
        logger.info("Blocked host: {}, {}, {} ({})", userLogin, sourceHost, forwardedFor.orElse(""));
        blockedHostMeter.mark();
        return new CaptchaRequirement(true, false);
        // isCaptchaRequired = true will send 402 to this guy
      }
    }

    try {
      rateLimiters.getSmsVoiceIpLimiter().validate(sourceHost);
    } catch (RateLimitExceededException e) {
      logger.info("Rate limited exceeded: {}, {}, {} ({})", userLogin, sourceHost, forwardedFor.orElse(""));
      rateLimitedHostMeter.mark();
      return new CaptchaRequirement(true, true);
      // send him 402 and consider for autoblock
    }

    return new CaptchaRequirement(false, false);
  }

  @Timed
  @DELETE
  @Path("/me")
  public void deleteAccount(@Auth AuthenticatedAccount auth) {
    accounts.delete(Stream.of(auth.getAccount()).collect(Collectors.toCollection(HashSet::new)), AccountsManager.DeletionReason.USER_REQUEST);
  }

  private boolean shouldAutoBlock(String sourceHost) {
    try {
      rateLimiters.getAutoBlockLimiter().validate(sourceHost);
    } catch (RateLimitExceededException e) {
      return true;
    }

    return false;
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
