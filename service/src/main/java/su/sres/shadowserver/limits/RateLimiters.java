/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.limits;

import java.util.concurrent.atomic.AtomicReference;

import su.sres.shadowserver.configuration.RateLimitsConfiguration;
import su.sres.shadowserver.configuration.RateLimitsConfiguration.RateLimitConfiguration;
import su.sres.shadowserver.configuration.dynamic.DynamicRateLimitsConfiguration;
import su.sres.shadowserver.redis.FaultTolerantRedisCluster;
import su.sres.shadowserver.redis.ReplicatedJedisPool;

public class RateLimiters {

    private final RateLimiter smsDestinationLimiter;
    private final RateLimiter voiceDestinationLimiter;
    private final RateLimiter voiceDestinationDailyLimiter;
    private final RateLimiter smsVoiceIpLimiter;
    private final RateLimiter smsVoicePrefixLimiter;
    private final RateLimiter autoBlockLimiter;
    private final RateLimiter verifyLimiter;
    private final RateLimiter pinLimiter;

    private final RateLimiter attachmentLimiter;
    private final RateLimiter contactsLimiter;
    private final RateLimiter preKeysLimiter;
    private final RateLimiter messagesLimiter;

    private final RateLimiter allocateDeviceLimiter;
    private final RateLimiter verifyDeviceLimiter;

    private final RateLimiter turnLimiter;

    private final RateLimiter profileLimiter;
    private final RateLimiter stickerPackLimiter;

    private final RateLimiter usernameLookupLimiter;
    private final RateLimiter usernameSetLimiter;

    private final RateLimiter configLimiter;
    private final RateLimiter certLimiter;
    private final RateLimiter certVerLimiter;
    private final RateLimiter directoryLimiter;
    private final RateLimiter licenseLimiter;

    private final AtomicReference<RateLimiter> unsealedSenderLimiter;
    private final AtomicReference<RateLimiter> unsealedIpLimiter;

    private final FaultTolerantRedisCluster cacheCluster;
    private final DynamicRateLimitsConfiguration dynamicConfig;

    public RateLimiters(RateLimitsConfiguration config, DynamicRateLimitsConfiguration dynamicConfig, FaultTolerantRedisCluster cacheCluster) {
	this.cacheCluster = cacheCluster;
	this.dynamicConfig = dynamicConfig;

	this.smsDestinationLimiter = new RateLimiter(cacheCluster, "smsDestination",
		config.getSmsDestination().getBucketSize(), config.getSmsDestination().getLeakRatePerMinute());

	this.voiceDestinationLimiter = new RateLimiter(cacheCluster, "voxDestination",
		config.getVoiceDestination().getBucketSize(), config.getVoiceDestination().getLeakRatePerMinute());

	this.voiceDestinationDailyLimiter = new RateLimiter(cacheCluster, "voxDestinationDaily",
		config.getVoiceDestinationDaily().getBucketSize(),
		config.getVoiceDestinationDaily().getLeakRatePerMinute());

	this.smsVoiceIpLimiter = new RateLimiter(cacheCluster, "smsVoiceIp",
		config.getSmsVoiceIp().getBucketSize(),
		config.getSmsVoiceIp().getLeakRatePerMinute());

	this.smsVoicePrefixLimiter = new RateLimiter(cacheCluster, "smsVoicePrefix",
		config.getSmsVoicePrefix().getBucketSize(), config.getSmsVoicePrefix().getLeakRatePerMinute());

	this.autoBlockLimiter = new RateLimiter(cacheCluster, "autoBlock",
		config.getAutoBlock().getBucketSize(),
		config.getAutoBlock().getLeakRatePerMinute());

	this.verifyLimiter = new LockingRateLimiter(cacheCluster, "verify",
		config.getVerifyUserLogin().getBucketSize(),
		config.getVerifyUserLogin().getLeakRatePerMinute());

	this.pinLimiter = new LockingRateLimiter(cacheCluster, "pin",
		config.getVerifyPin().getBucketSize(),
		config.getVerifyPin().getLeakRatePerMinute());

	this.attachmentLimiter = new RateLimiter(cacheCluster, "attachmentCreate",
		config.getAttachments().getBucketSize(), config.getAttachments().getLeakRatePerMinute());

	this.contactsLimiter = new RateLimiter(cacheCluster, "contactsQuery",
		config.getContactQueries().getBucketSize(),
		config.getContactQueries().getLeakRatePerMinute());

	this.preKeysLimiter = new RateLimiter(cacheCluster, "prekeys",
		config.getPreKeys().getBucketSize(),
		config.getPreKeys().getLeakRatePerMinute());

	this.messagesLimiter = new RateLimiter(cacheCluster, "messages",
		config.getMessages().getBucketSize(),
		config.getMessages().getLeakRatePerMinute());

	this.allocateDeviceLimiter = new RateLimiter(cacheCluster, "allocateDevice",
		config.getAllocateDevice().getBucketSize(), config.getAllocateDevice().getLeakRatePerMinute());

	this.verifyDeviceLimiter = new RateLimiter(cacheCluster, "verifyDevice",
		config.getVerifyDevice().getBucketSize(), config.getVerifyDevice().getLeakRatePerMinute());

	this.turnLimiter = new RateLimiter(cacheCluster, "turnAllocate",
		config.getTurnAllocations().getBucketSize(),
		config.getTurnAllocations().getLeakRatePerMinute());

	this.profileLimiter = new RateLimiter(cacheCluster, "profile",
		config.getProfile().getBucketSize(),
		config.getProfile().getLeakRatePerMinute());

	this.stickerPackLimiter = new RateLimiter(cacheCluster, "stickerPack",
		config.getStickerPack().getBucketSize(),
		config.getStickerPack().getLeakRatePerMinute());

	this.usernameLookupLimiter = new RateLimiter(cacheCluster, "usernameLookup",
		config.getUsernameLookup().getBucketSize(), config.getUsernameLookup().getLeakRatePerMinute());

	this.usernameSetLimiter = new RateLimiter(cacheCluster, "usernameSet",
		config.getUsernameSet().getBucketSize(),
		config.getUsernameSet().getLeakRatePerMinute());

	this.configLimiter = new RateLimiter(cacheCluster, "configRequest", config.getConfigRequest().getBucketSize(),
		config.getConfigRequest().getLeakRatePerMinute());

	this.certLimiter = new RateLimiter(cacheCluster, "certRequest", config.getCertRequest().getBucketSize(),
		config.getCertRequest().getLeakRatePerMinute());

	this.certVerLimiter = new RateLimiter(cacheCluster, "certVerRequest", config.getCertVerRequest().getBucketSize(),
		config.getCertVerRequest().getLeakRatePerMinute());

	this.directoryLimiter = new RateLimiter(cacheCluster, "directoryRequest", config.getDirectoryRequest().getBucketSize(),
		config.getDirectoryRequest().getLeakRatePerMinute());

	this.licenseLimiter = new RateLimiter(cacheCluster, "licenseRequest", config.getLicenseRequest().getBucketSize(),
		config.getLicenseRequest().getLeakRatePerMinute());

	this.unsealedSenderLimiter = new AtomicReference<>(createUnsealedSenderLimiter(cacheCluster, dynamicConfig.getUnsealedSenderNumber()));
	this.unsealedIpLimiter = new AtomicReference<>(createUnsealedIpLimiter(cacheCluster, dynamicConfig.getUnsealedSenderIp()));
    }

    public RateLimiter getUnsealedSenderLimiter() {
	RateLimitConfiguration currentConfiguration = dynamicConfig.getUnsealedSenderNumber();

	return this.unsealedSenderLimiter.updateAndGet(rateLimiter -> {
	    if (isLimiterConfigurationCurrent(rateLimiter, currentConfiguration)) {
		return rateLimiter;
	    } else {
		return createUnsealedSenderLimiter(cacheCluster, currentConfiguration);
	    }
	});
    }

    public RateLimiter getUnsealedIpLimiter() {
	RateLimitConfiguration currentConfiguration = dynamicConfig.getUnsealedSenderIp();

	return this.unsealedIpLimiter.updateAndGet(rateLimiter -> {
	    if (isLimiterConfigurationCurrent(rateLimiter, currentConfiguration)) {
		return rateLimiter;
	    } else {
		return createUnsealedIpLimiter(cacheCluster, currentConfiguration);
	    }
	});
    }

    public RateLimiter getAllocateDeviceLimiter() {
	return allocateDeviceLimiter;
    }

    public RateLimiter getVerifyDeviceLimiter() {
	return verifyDeviceLimiter;
    }

    public RateLimiter getMessagesLimiter() {
	return messagesLimiter;
    }

    public RateLimiter getPreKeysLimiter() {
	return preKeysLimiter;
    }

    public RateLimiter getContactsLimiter() {
	return contactsLimiter;
    }

    public RateLimiter getAttachmentLimiter() {
	return this.attachmentLimiter;
    }

    public RateLimiter getSmsDestinationLimiter() {
	return smsDestinationLimiter;
    }

    public RateLimiter getSmsVoiceIpLimiter() {
	return smsVoiceIpLimiter;
    }

    public RateLimiter getSmsVoicePrefixLimiter() {
	return smsVoicePrefixLimiter;
    }

    public RateLimiter getAutoBlockLimiter() {
	return autoBlockLimiter;
    }

    public RateLimiter getVoiceDestinationLimiter() {
	return voiceDestinationLimiter;
    }

    public RateLimiter getVoiceDestinationDailyLimiter() {
	return voiceDestinationDailyLimiter;
    }

    public RateLimiter getVerifyLimiter() {
	return verifyLimiter;
    }

    public RateLimiter getPinLimiter() {
	return pinLimiter;
    }

    public RateLimiter getTurnLimiter() {
	return turnLimiter;
    }

    public RateLimiter getProfileLimiter() {
	return profileLimiter;
    }

    public RateLimiter getStickerPackLimiter() {
	return stickerPackLimiter;
    }

    public RateLimiter getUsernameLookupLimiter() {
	return usernameLookupLimiter;
    }

    public RateLimiter getUsernameSetLimiter() {
	return usernameSetLimiter;
    }

    public RateLimiter getConfigLimiter() {
	return configLimiter;
    }

    public RateLimiter getCertLimiter() {
	return certLimiter;
    }

    public RateLimiter getCertVerLimiter() {
	return certVerLimiter;
    }

    public RateLimiter getDirectoryLimiter() {
	return directoryLimiter;
    }

    public RateLimiter getLicenseLimiter() {
	return licenseLimiter;
    }

    private RateLimiter createUnsealedSenderLimiter(FaultTolerantRedisCluster cacheCluster,
	    RateLimitConfiguration configuration) {
	return createLimiter(cacheCluster, configuration, "unsealedSender");
    }

    private RateLimiter createUnsealedIpLimiter(FaultTolerantRedisCluster cacheCluster,
	    RateLimitConfiguration configuration) {
	return createLimiter(cacheCluster, configuration, "unsealedIp");
    }

    private RateLimiter createLimiter(FaultTolerantRedisCluster cacheCluster, RateLimitConfiguration configuration, String name) {
	return new RateLimiter(cacheCluster, name,
		configuration.getBucketSize(),
		configuration.getLeakRatePerMinute());
    }

    private boolean isLimiterConfigurationCurrent(RateLimiter limiter, RateLimitConfiguration configuration) {
	return limiter.getBucketSize() == configuration.getBucketSize() && limiter.getLeakRatePerMinute() == configuration.getLeakRatePerMinute();
    }
}
