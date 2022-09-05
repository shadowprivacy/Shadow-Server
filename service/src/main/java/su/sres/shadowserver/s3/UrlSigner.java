/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.s3;


import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import io.minio.errors.InsufficientDataException;
import io.minio.errors.InternalException;
import io.minio.errors.InvalidBucketNameException;
import io.minio.errors.InvalidExpiresRangeException;
import io.minio.errors.InvalidResponseException;
import io.minio.errors.ServerException;
import io.minio.errors.XmlParserException;
import io.minio.http.Method;

import java.io.IOException;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class UrlSigner {

  private static final int DURATION = 60 * 60 * 1000;
 
  private final String accessKey;
  private final String accessSecret;
  private final String bucket;
  private final String endpoint;

  public UrlSigner(String accessKey, String accessSecret, String bucket, String endpoint) {    
    this.accessKey = accessKey;
    this.accessSecret = accessSecret;
    this.bucket = bucket;
    this.endpoint = endpoint;    
  }

  public URL getPreSignedUrl(long attachmentId, Method method) throws InvalidKeyException, ErrorResponseException, IllegalArgumentException, InsufficientDataException, InternalException, InvalidBucketNameException, InvalidExpiresRangeException, InvalidResponseException, NoSuchAlgorithmException, XmlParserException, ServerException, IOException {
        
    Map<String,String> extraHeaders = Collections.singletonMap("Content-Type", "application/octet-stream");
    
    MinioClient client = MinioClient.builder().credentials(accessKey, accessSecret).endpoint(endpoint).build();
               
    GetPresignedObjectUrlArgs args = GetPresignedObjectUrlArgs.builder()
        .bucket(bucket)
        .object(String.valueOf(attachmentId))
        .method(method)
        .expiry(DURATION, TimeUnit.MILLISECONDS)
        .extraHeaders(extraHeaders)
        .build();    
    
    System.out.println(client.getPresignedObjectUrl(args));
    
    return new URL(client.getPresignedObjectUrl(args));        
  }
}
