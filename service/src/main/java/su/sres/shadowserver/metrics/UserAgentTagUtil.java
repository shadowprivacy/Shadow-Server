/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.metrics;

import io.micrometer.core.instrument.Tag;
import su.sres.shadowserver.util.Pair;
import su.sres.shadowserver.util.ua.ClientPlatform;
import su.sres.shadowserver.util.ua.UnrecognizedUserAgentException;
import su.sres.shadowserver.util.ua.UserAgent;
import su.sres.shadowserver.util.ua.UserAgentUtil;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vdurmont.semver4j.Semver;

/**
 * Utility class for extracting platform/version metrics tags from User-Agent
 * strings.
 */
public class UserAgentTagUtil {

    public static final String PLATFORM_TAG = "platform";
    public static final String VERSION_TAG = "clientVersion";
    static final List<Tag> OVERFLOW_TAGS = List.of(Tag.of(PLATFORM_TAG, "overflow"), Tag.of(VERSION_TAG, "overflow"));
    static final List<Tag> UNRECOGNIZED_TAGS = List.of(Tag.of(PLATFORM_TAG, "unrecognized"), Tag.of(VERSION_TAG, "unrecognized"));

    private static final Map<ClientPlatform, Semver> MINIMUM_VERSION_BY_PLATFORM = new EnumMap<>(ClientPlatform.class);

    static {
	MINIMUM_VERSION_BY_PLATFORM.put(ClientPlatform.ANDROID, new Semver("4.0.0"));
	MINIMUM_VERSION_BY_PLATFORM.put(ClientPlatform.DESKTOP, new Semver("1.0.0"));
	MINIMUM_VERSION_BY_PLATFORM.put(ClientPlatform.IOS, new Semver("3.0.0"));
    }

    static final int MAX_VERSIONS = 1_000;
    private static final Set<Pair<ClientPlatform, Semver>> SEEN_VERSIONS = new HashSet<>();

    private UserAgentTagUtil() {
    }

    public static List<Tag> getUserAgentTags(final String userAgentString) {
	try {
	    final UserAgent userAgent = UserAgentUtil.parseUserAgentString(userAgentString);
	    final List<Tag> tags;

	    if (userAgent.getVersion().isStable() && userAgent.getVersion().isGreaterThanOrEqualTo(MINIMUM_VERSION_BY_PLATFORM.get(userAgent.getPlatform()))) {
		if (allowVersion(userAgent.getPlatform(), userAgent.getVersion())) {
		    tags = List.of(Tag.of(PLATFORM_TAG, userAgent.getPlatform().name().toLowerCase()), Tag.of(VERSION_TAG, userAgent.getVersion().toString()));
		} else {
		    tags = OVERFLOW_TAGS;
		}

	    } else {
		tags = UNRECOGNIZED_TAGS;
	    }
	    return tags;
	} catch (final UnrecognizedUserAgentException e) {
	    return UNRECOGNIZED_TAGS;
	}
    }

    private static boolean allowVersion(final ClientPlatform platform, final Semver version) {
	final Pair<ClientPlatform, Semver> platformAndVersion = new Pair<>(platform, version);

	synchronized (SEEN_VERSIONS) {
	    return SEEN_VERSIONS.contains(platformAndVersion) || (SEEN_VERSIONS.size() < MAX_VERSIONS && SEEN_VERSIONS.add(platformAndVersion));
	}
    }
}