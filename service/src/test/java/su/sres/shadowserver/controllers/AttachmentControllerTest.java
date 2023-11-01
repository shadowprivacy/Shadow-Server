/*
 * Original software: Copyright 2013-2021 Signal Messenger, LLC
 * Modified software: Copyright 2019-2023 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.controllers;

import com.google.common.collect.ImmutableSet;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import io.dropwizard.auth.PolymorphicAuthValueFactoryProvider;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import io.findify.s3mock.S3Mock;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.MinioException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import su.sres.shadowserver.auth.AuthenticatedAccount;
import su.sres.shadowserver.auth.DisabledPermittedAuthenticatedAccount;
import su.sres.shadowserver.entities.AttachmentDescriptorV1;
import su.sres.shadowserver.entities.AttachmentDescriptorV2;
import su.sres.shadowserver.entities.AttachmentUri;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.AuthHelper;
import su.sres.shadowserver.util.SystemMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class AttachmentControllerTest {

  private static final String KEY = "accessKey";
  private static final String SECRET = "accessSecret";
  private static final String ENDPOINT = "http://localhost:9000";
  private static final String BUCKET = "attachment-bucket";

  private static RateLimiters rateLimiters = mock(RateLimiters.class);
  private static RateLimiter rateLimiter = mock(RateLimiter.class);

  final S3Mock api = S3Mock.create(9000);

  static {
    when(rateLimiters.getAttachmentLimiter()).thenReturn(rateLimiter);
  }

  @Before
  public void init() throws MinioException, InvalidKeyException, NoSuchAlgorithmException, IllegalArgumentException, IOException {

    api.start();

    MinioClient client = MinioClient.builder().credentials(KEY, SECRET).endpoint(ENDPOINT).build();
    client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
  }

  @After
  public void stop() {
    api.stop();
  }
 
  public static final ResourceExtension resources = ResourceExtension.builder()
      .addProvider(AuthHelper.getAuthFilter())
      .addProvider(new PolymorphicAuthValueFactoryProvider.Binder<>(ImmutableSet.of(AuthenticatedAccount.class, DisabledPermittedAuthenticatedAccount.class)))
      .setMapper(SystemMapper.getMapper())
      .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
      .addResource(new AttachmentControllerV1(rateLimiters, KEY, SECRET, BUCKET, ENDPOINT))
      .addResource(new AttachmentControllerV2(rateLimiters, KEY, SECRET, "us-east-1", "attachmentv2-bucket"))
      .build();

  @Test
  void testV2Form() throws IOException {
    AttachmentDescriptorV2 descriptor = resources.getJerseyTest()
        .target("/v2/attachments/form/upload")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID, AuthHelper.VALID_PASSWORD))
        .get(AttachmentDescriptorV2.class);

    assertThat(descriptor.getKey()).isEqualTo(descriptor.getAttachmentIdString());
    assertThat(descriptor.getAcl()).isEqualTo("private");
    assertThat(descriptor.getAlgorithm()).isEqualTo("AWS4-HMAC-SHA256");
    assertThat(descriptor.getAttachmentId()).isGreaterThan(0);
    assertThat(String.valueOf(descriptor.getAttachmentId())).isEqualTo(descriptor.getAttachmentIdString());

    String[] credentialParts = descriptor.getCredential().split("/");

    assertThat(credentialParts[0]).isEqualTo("accessKey");
    assertThat(credentialParts[2]).isEqualTo("us-east-1");
    assertThat(credentialParts[3]).isEqualTo("s3");
    assertThat(credentialParts[4]).isEqualTo("aws4_request");

    assertThat(descriptor.getDate()).isNotBlank();
    assertThat(descriptor.getPolicy()).isNotBlank();
    assertThat(descriptor.getSignature()).isNotBlank();

    assertThat(new String(Base64.getDecoder().decode(descriptor.getPolicy()))).contains("[\"content-length-range\", 1, 104857600]");
  }

  @Test
  void testV2FormDisabled() {
    Response response = resources.getJerseyTest()
        .target("/v2/attachments/form/upload")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.DISABLED_UUID, AuthHelper.DISABLED_PASSWORD))
        .get();

    assertThat(response.getStatus()).isEqualTo(401);
  }

  @Test
  void testUnacceleratedPut() {

    AttachmentDescriptorV1 descriptor = resources.getJerseyTest()
        .target("/v1/attachments/")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .get(AttachmentDescriptorV1.class);

    assertThat(descriptor.getLocation()).startsWith("http://localhost");
    assertThat(descriptor.getId()).isGreaterThan(0);
    assertThat(descriptor.getIdString()).isNotBlank();
  }

  @Test
  void testUnacceleratedGet() throws InvalidKeyException, ErrorResponseException, InsufficientDataException, InternalException, InvalidResponseException, NoSuchAlgorithmException, ServerException, XmlParserException, IllegalArgumentException, IOException {

    AttachmentUri uri = resources.getJerseyTest()
        .target("/v1/attachments/1234")
        .request()
        .header("Authorization", AuthHelper.getAuthHeader(AuthHelper.VALID_UUID_TWO, AuthHelper.VALID_PASSWORD_TWO))
        .get(AttachmentUri.class);

    assertThat(uri.getLocation().getHost()).isEqualTo("localhost");
  }
}
