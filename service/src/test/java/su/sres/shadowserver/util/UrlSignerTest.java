/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

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
import io.minio.http.Method;
import su.sres.shadowserver.s3.UrlSigner;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;

public class UrlSignerTest {
  
  final S3Mock api = S3Mock.create(9000);
  
  @Before
  public void init() throws MinioException, InvalidKeyException, NoSuchAlgorithmException, IllegalArgumentException, IOException {

    api.start();

    MinioClient client = MinioClient.builder().credentials("foo", "bar").endpoint("http://localhost:9000").build();
    client.makeBucket(MakeBucketArgs.builder().bucket("attachments-test").build());
  }

  @After
  public void stop() {
    api.stop();
  }

  @Test
  public void testTransferUnaccelerated() throws InvalidKeyException, ErrorResponseException, IllegalArgumentException, InsufficientDataException, InternalException, InvalidResponseException, NoSuchAlgorithmException, XmlParserException, ServerException, IOException {
    UrlSigner signer = new UrlSigner("foo", "bar", "attachments-test", "http://localhost:9000");
    URL url = signer.getPreSignedUrl(1234, Method.GET);

    assertThat(url).hasHost("localhost");
  }
}
