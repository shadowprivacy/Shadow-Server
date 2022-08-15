/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.constraints.NotNull;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class RemoteConfigConfiguration {

    @JsonProperty
    @NotNull
    private List<String> authorizedTokens = new LinkedList<>();

    @NotNull
    @JsonProperty
    private Map<String, String> globalConfig = new HashMap<>();

    public List<String> getAuthorizedTokens() {
	return authorizedTokens;
    }

    public Map<String, String> getGlobalConfig() {
	return globalConfig;
    }
}
