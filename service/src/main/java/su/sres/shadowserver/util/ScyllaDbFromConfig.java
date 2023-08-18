package su.sres.shadowserver.util;

import su.sres.shadowserver.configuration.ScyllaDbConfiguration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientAsyncConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;
import java.util.concurrent.Executor;

public class ScyllaDbFromConfig {
  private static ClientOverrideConfiguration clientOverrideConfiguration(ScyllaDbConfiguration config) {
    return ClientOverrideConfiguration.builder()
        .apiCallTimeout(config.getClientExecutionTimeout())
        .apiCallAttemptTimeout(config.getClientRequestTimeout())
        .build();
  }
  public static DynamoDbClient client(ScyllaDbConfiguration config) {
    return DynamoDbClient.builder()    
        .region(Region.of(config.getRegion()))
        .endpointOverride(URI.create(config.getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getAccessKey(), config.getAccessSecret())))
        .overrideConfiguration(clientOverrideConfiguration(config))
        .build();
  }
  public static DynamoDbAsyncClient asyncClient(ScyllaDbConfiguration config, Executor executor) {
    DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
        .region(Region.of(config.getRegion()))
        .endpointOverride(URI.create(config.getEndpoint()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getAccessKey(), config.getAccessSecret())))
        .overrideConfiguration(clientOverrideConfiguration(config));
    if (executor != null) {
      builder.asyncConfiguration(ClientAsyncConfiguration.builder()
          .advancedOption(SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR,
              executor)
          .build());
    }
    return builder.build();
  }
}
