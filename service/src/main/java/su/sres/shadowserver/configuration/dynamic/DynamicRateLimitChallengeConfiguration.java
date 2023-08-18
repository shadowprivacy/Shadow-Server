package su.sres.shadowserver.configuration.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.vdurmont.semver4j.Semver;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import su.sres.shadowserver.util.ua.ClientPlatform;
import javax.validation.constraints.NotNull;

public class DynamicRateLimitChallengeConfiguration {
  @JsonProperty
  private boolean preKeyLimitEnforced = true;

  @JsonProperty
  boolean unsealedSenderLimitEnforced = true;

  @JsonProperty
  @NotNull
  private Map<ClientPlatform, Semver> clientSupportedVersions = Collections.emptyMap();

  @VisibleForTesting
  Map<ClientPlatform, Semver> getClientSupportedVersions() {
    return clientSupportedVersions;
  }

  public Optional<Semver> getMinimumSupportedVersion(final ClientPlatform platform) {
    return Optional.ofNullable(clientSupportedVersions.get(platform));
  }

  public boolean isPreKeyLimitEnforced() {
    return preKeyLimitEnforced;
  }

  public boolean isUnsealedSenderLimitEnforced() {
    return unsealedSenderLimitEnforced;
  }

}
