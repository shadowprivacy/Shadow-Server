package su.sres.shadowserver.util;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.MinioException;
import su.sres.shadowserver.configuration.MinioConfiguration;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ObjectMonitorTest {

  @Test
  void refresh() throws InvalidKeyException, MinioException, IOException, IllegalArgumentException, NoSuchAlgorithmException {
    final MinioClient s3Client = mock(MinioClient.class);
    final InputStream bis = new ByteArrayInputStream(new byte[1]);
    final StatObjectResponse sResponse = mock(StatObjectResponse.class);
    
    final String bucket = "s3bucket";
    final String objectKey = "greatest-smooth-jazz-hits-of-all-time.zip";
    
    final GetObjectResponse gResponse = new GetObjectResponse(null, bucket, "region", objectKey, bis);
        
    //noinspection unchecked
    final Consumer<InputStream> listener = mock(Consumer.class);
    final byte[] b = new byte[18000];
        
    final S3ObjectMonitor objectMonitor = new S3ObjectMonitor(
        s3Client,
        bucket,        
        objectKey,
        16 * 1024 * 1024,
        mock(ScheduledExecutorService.class),
        Duration.ofMinutes(1),
        listener);
    
    String uuid = UUID.randomUUID().toString();

    when(sResponse.etag()).thenReturn(UUID.randomUUID().toString());
    
    when(s3Client.statObject(any(StatObjectArgs.class))).thenReturn(sResponse);
    when(s3Client.getObject(any(GetObjectArgs.class))).thenReturn(gResponse);
          
    objectMonitor.refresh();    
    objectMonitor.refresh();

    verify(listener).accept(gResponse);
  }
  
  @Test
  void refreshAfterGet() throws IOException, MinioException, InvalidKeyException, IllegalArgumentException, NoSuchAlgorithmException {
    final MinioClient s3Client = mock(MinioClient.class);
    final InputStream bis = new ByteArrayInputStream(new byte[1]);
     
    final StatObjectResponse sResponse = mock(StatObjectResponse.class);
    final MinioConfiguration tenConfig = mock(MinioConfiguration.class);

    final String bucket = "s3bucket";
    final String objectKey = "greatest-smooth-jazz-hits-of-all-time.zip";
    
    final GetObjectResponse gResponse = new GetObjectResponse(null, bucket, "region", objectKey, bis);

    //noinspection unchecked
    final Consumer<InputStream> listener = mock(Consumer.class);

    final S3ObjectMonitor objectMonitor = new S3ObjectMonitor(
        s3Client,
        bucket,        
        objectKey,
        16 * 1024 * 1024,
        mock(ScheduledExecutorService.class),
        Duration.ofMinutes(1),
        listener);

    when(sResponse.etag()).thenReturn(UUID.randomUUID().toString());
    
    when(s3Client.statObject(any(StatObjectArgs.class))).thenReturn(sResponse);
    when(s3Client.getObject(any(GetObjectArgs.class))).thenReturn(gResponse);

    objectMonitor.getObject();
    objectMonitor.refresh();

    verify(listener, never()).accept(gResponse);
  }
  
  @Test
  void refreshOversizedObject() throws MinioException, IOException, InvalidKeyException, IllegalArgumentException, NoSuchAlgorithmException {
    final MinioClient s3Client = mock(MinioClient.class);
    
    final StatObjectResponse sResponse = mock(StatObjectResponse.class);
    
    final String bucket = "s3bucket";
    final String objectKey = "greatest-smooth-jazz-hits-of-all-time.zip";
    final int maxObjectSize = 16 * 1024 * 1024;
    
    final InputStream bis = new ByteArrayInputStream(new byte[maxObjectSize + 1]);
    final GetObjectResponse gResponse = new GetObjectResponse(null, bucket, "region", objectKey, bis);    
        
    //noinspection unchecked
    final Consumer<InputStream> listener = mock(Consumer.class);     

    final S3ObjectMonitor objectMonitor = new S3ObjectMonitor(
        s3Client,
        bucket,
        objectKey,
        maxObjectSize,
        mock(ScheduledExecutorService.class),
        Duration.ofMinutes(1),
        listener);
    
    when(s3Client.statObject(any(StatObjectArgs.class))).thenReturn(sResponse);
    when(s3Client.getObject(any(GetObjectArgs.class))).thenReturn(gResponse);
            
    when(sResponse.etag()).thenReturn(UUID.randomUUID().toString());          
    when(sResponse.size()).thenReturn((long) (maxObjectSize + 1));
    
    objectMonitor.refresh();

    verify(listener, never()).accept(any());
  }
}
