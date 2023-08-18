package su.sres.shadowserver.configuration;

import javax.validation.constraints.NotNull;

public class AccountsScyllaDbConfiguration extends ScyllaDbConfiguration {

  @NotNull
  private String userLoginTableName;
  
  @NotNull
  private String miscTableName;

  public String getUserLoginTableName() {
    return userLoginTableName;
  }
  
  public String getMiscTableName() {
    return miscTableName;
  }
  
}
