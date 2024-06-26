/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AnswerPushChallengeRequest.class, name = "rateLimitPushChallenge"),
    @JsonSubTypes.Type(value = AnswerRecaptchaChallengeRequest.class, name = "recaptcha")
})
public abstract class AnswerChallengeRequest {
}
