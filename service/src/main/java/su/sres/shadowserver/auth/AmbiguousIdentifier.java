package su.sres.shadowserver.auth;

import java.util.UUID;

public class AmbiguousIdentifier {

  private final UUID   uuid;
  private final String userLogin;

  public AmbiguousIdentifier(String target) {
    if (!isUuid(target)) {
      this.uuid   = null;
      this.userLogin = target;
    } else {
      this.uuid   = UUID.fromString(target);
      this.userLogin = null;
    }
  }

  public UUID getUuid() {
    return uuid;
  }

  public String getUserLogin() {
    return userLogin;
  }

  public boolean hasUuid() {
    return uuid != null;
  }

  public boolean hasUserLogin() {
    return userLogin != null;
  }
  
  private boolean isUuid(String test) {
	  return test.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-5][0-9a-f]{3}-[089ab][0-9a-f]{3}-[0-9a-f]{12}$");	  
  }
}