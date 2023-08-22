/*
 * Copyright 2013-2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.util;

import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.lifecycle.Managed;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.MinioException;
import su.sres.shadowserver.configuration.MinioConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An S3 object monitor watches a specific object in an S3 bucket and notifies a
 * listener if that object changes.
 */
public class S3ObjectMonitor implements Managed {

  private final String s3Bucket;
  private final String objectKey;
  private final long maxObjectSize;

  private final ScheduledExecutorService refreshExecutorService;
  private final Duration refreshInterval;
  private ScheduledFuture<?> refreshFuture;

  private final Consumer<InputStream> changeListener;

  private final AtomicReference<String> lastETag = new AtomicReference<>();

  private final MinioClient s3Client;

  private static final Logger log = LoggerFactory.getLogger(S3ObjectMonitor.class);

  public S3ObjectMonitor(
      final MinioConfiguration config,
      final String objectKey,
      final long maxObjectSize,
      final ScheduledExecutorService refreshExecutorService,
      final Duration refreshInterval,
      final Consumer<InputStream> changeListener) {

    this(MinioClient.builder()
        .endpoint(config.getUri())
        .credentials(config.getAccessKey(), config.getAccessSecret())
        .region(config.getRegion())
        .build(),
        config.getServiceBucket(),
        objectKey,
        maxObjectSize,
        refreshExecutorService,
        refreshInterval,
        changeListener);
  }

  @VisibleForTesting
  S3ObjectMonitor(
      final MinioClient s3Client,
      final String s3Bucket,
      final String objectKey,
      final long maxObjectSize,
      final ScheduledExecutorService refreshExecutorService,
      final Duration refreshInterval,
      final Consumer<InputStream> changeListener) {

    this.s3Client = s3Client;
    this.s3Bucket = s3Bucket;
    this.objectKey = objectKey;
    this.maxObjectSize = maxObjectSize;

    this.refreshExecutorService = refreshExecutorService;
    this.refreshInterval = refreshInterval;

    this.changeListener = changeListener;
  }

  @Override
  public synchronized void start() {
    if (refreshFuture != null) {
      throw new RuntimeException("S3 object manager already started");
    }

    // Run the first request immediately/blocking, then start subsequent calls.
    log.info("Initial request for s3://{}/{}", s3Bucket, objectKey);
    refresh();

    refreshFuture = refreshExecutorService
        .scheduleAtFixedRate(this::refresh, refreshInterval.toMillis(), refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (refreshFuture != null) {
      refreshFuture.cancel(true);
    }
  }

  /**
   * Immediately returns the monitored S3 object regardless of whether it has
   * changed since it was last retrieved.
   *
   * @return the current version of the monitored S3 object. Caller should close()
   *         this upon completion.
   * @throws IOException              if the retrieved S3 object is larger than
   *                                  the configured maximum size
   * @throws NoSuchAlgorithmException
   * @throws MinioException
   * @throws IllegalArgumentException
   * @throws InvalidKeyException
   */
  @VisibleForTesting
  GetObjectResponse getObject() throws IOException, MinioException, InvalidKeyException, IllegalArgumentException, NoSuchAlgorithmException {
    GetObjectArgs getArgs = GetObjectArgs.builder()
        .bucket(s3Bucket)
        .object(objectKey)
        .build();

    StatObjectArgs statArgs = StatObjectArgs.builder()
        .bucket(s3Bucket)
        .object(objectKey)
        .build();

    GetObjectResponse gResponse = s3Client.getObject(getArgs);
    StatObjectResponse sResponse = s3Client.statObject(statArgs);

    lastETag.set(sResponse.etag());

    int size = Util.getBytes(gResponse).length;

    if (size <= maxObjectSize) {
      return gResponse;
    } else {
      log.warn("Object at s3://{}/{} has a size of {} bytes, which exceeds the maximum allowed size of {} bytes",
          s3Bucket, objectKey, size, maxObjectSize);

      gResponse.close();

      throw new IOException("S3 object too large");
    }
  }

  /**
   * Polls S3 for object metadata and notifies the listener provided at
   * construction time if and only if the object has changed since the last call
   * to {@link #getObject()} or {@code refresh()}.
   */
  @VisibleForTesting
  void refresh() {
    try {
      final String initialETag = lastETag.get();

      GetObjectArgs getArgs = GetObjectArgs.builder()
          .bucket(s3Bucket)
          .object(objectKey)
          .build();

      StatObjectArgs statArgs = StatObjectArgs.builder()
          .bucket(s3Bucket)
          .object(objectKey)
          .build();

      StatObjectResponse sResponse = s3Client.statObject(statArgs);
      final String refreshedETag = sResponse.etag();

      if (!StringUtils.equals(initialETag, refreshedETag) && lastETag.compareAndSet(initialETag, refreshedETag)) {

        try (final GetObjectResponse gResponse = getObject()) {

        int size = Util.getBytes(gResponse).length;

        log.info("Object at s3://{}/{} has changed; new eTag is {} and object size is {} bytes",
            s3Bucket, objectKey, refreshedETag, size);               
        
          changeListener.accept(gResponse);
        } 

      }
    } catch (final Exception e) {
      log.warn("Failed to refresh monitored object", e);
    }
  }
}
