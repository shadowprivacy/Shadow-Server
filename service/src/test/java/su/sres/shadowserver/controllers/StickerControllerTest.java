/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.DisabledPermittedAuthenticatedAccount;
import su.sres.shadowserver.entities.StickerPackFormUploadAttributes;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.SystemMapper;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.util.Base64;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;

@ExtendWith(DropwizardExtensionsSupport.class)
class StickerControllerTest {

  private static final RateLimiter rateLimiter = mock(RateLimiter.class);
  private static final RateLimiters rateLimiters = mock(RateLimiters.class);

  private static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(AuthenticatedAccount.class, DisabledPermittedAuthenticatedAccount.class)))
      .setMapper(SystemMapper.getMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new StickerController(rateLimiters, "foo", "bar", "us-east-1", "mybucket"))
      .build();

  @BeforeEach
  void setup() {
    when(rateLimiters.getStickerPackLimiter()).thenReturn(rateLimiter);
  }

  @Test
  void testCreatePack() throws RateLimitExceededException, IOException {
    StickerPackFormUploadAttributes attributes = resources.getJerseyTest().target("/v1/sticker/pack/form/10")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(StickerPackFormUploadAttributes.class);

    assertThat(attributes.getPackId()).isNotNull();
    assertThat(attributes.getPackId().length()).isEqualTo(32);

    assertThat(attributes.getManifest()).isNotNull();
    assertThat(attributes.getManifest().getKey())
        .isEqualTo("stickers/" + attributes.getPackId() + "/manifest.proto");
    assertThat(attributes.getManifest().getAcl()).isEqualTo("private");
    assertThat(attributes.getManifest().getPolicy()).isNotEmpty();
    assertThat(new String(Base64.getDecoder().decode(attributes.getManifest().getPolicy()))).contains("[\"content-length-range\", 1, 10240]");
    assertThat(attributes.getManifest().getSignature()).isNotEmpty();
    assertThat(attributes.getManifest().getAlgorithm()).isEqualTo("AWS4-HMAC-SHA256");
    assertThat(attributes.getManifest().getCredential()).isNotEmpty();
    assertThat(attributes.getManifest().getId()).isEqualTo(-1);

    assertThat(attributes.getStickers().size()).isEqualTo(10);

    for (int i = 0; i < 10; i++) {
      assertThat(attributes.getStickers().get(i).getId()).isEqualTo(i);
      assertThat(attributes.getStickers().get(i).getKey())
          .isEqualTo("stickers/" + attributes.getPackId() + "/full/" + i);
      assertThat(attributes.getStickers().get(i).getAcl()).isEqualTo("private");
      assertThat(attributes.getStickers().get(i).getPolicy()).isNotEmpty();
      assertThat(new String(Base64.getDecoder().decode(attributes.getStickers().get(i).getPolicy()))).contains("[\"content-length-range\", 1, 307200]");
      assertThat(attributes.getStickers().get(i).getSignature()).isNotEmpty();
      assertThat(attributes.getStickers().get(i).getAlgorithm()).isEqualTo("AWS4-HMAC-SHA256");
      assertThat(attributes.getStickers().get(i).getCredential()).isNotEmpty();
    }

    verify(rateLimiters, times(1)).getStickerPackLimiter();
    verify(rateLimiter, times(1)).validate(AuthHelper.VALID_UUID);
  }

  @Test
  void testCreateTooLargePack() throws Exception {
    Response response = resources.getJerseyTest().target("/v1/sticker/pack/form/202").request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(400);

  }

}