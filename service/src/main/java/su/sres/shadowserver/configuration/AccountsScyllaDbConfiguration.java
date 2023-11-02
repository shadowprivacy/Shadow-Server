package su.sres.shadowserver.configuration;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AccountsScyllaDbConfiguration extends ScyllaDbConfiguration {

  @NotNull
  private String userLoginTableName;
  
  @NotNull
  private String miscTableName;

  private int scanPageSize = 100;

  @JsonProperty
  public String getUserLoginTableName() {
    return userLoginTableName;
  }
  
  @JsonProperty
  public String getMiscTableName() {
    return miscTableName;
  }
  
  @JsonProperty
  public int getScanPageSize() {
    return scanPageSize;
  }
  
}
