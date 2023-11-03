/*
 * Copyright 2021 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package su.sres.shadowserver.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.time.Duration;

public class ScyllaDbConfiguration {

    private String endpoint;
    private String region;
           
    private String accessKey;  
    private String accessSecret;
    
    private String accountsTableName;   
    private String userLoginTableName;
    private String miscTableName;
    
    private String messagesTableName;
    
    private String keysTableName;
    private String pushChallengeTableName;
    private String reportMessageTableName;
    private String pendingAccountsTableName;
    private String pendingDevicesTableName;
    private String deletedAccountsTableName;
    private String groupsTableName;
    private String groupLogsTableName;
    
    private Duration clientExecutionTimeout = Duration.ofSeconds(30);
    private Duration clientRequestTimeout = Duration.ofSeconds(10);
    
    // used by accounts 
    private int scanPageSize = 100;
    
    // used by messages
    private Duration timeToLive = Duration.ofDays(14);
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getEndpoint() {
    return endpoint;
    }
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getRegion() {
	return region;
    }
    
    @JsonProperty
    public Duration getClientExecutionTimeout() {
	return clientExecutionTimeout;
    }

    @JsonProperty
    public Duration getClientRequestTimeout() {
	return clientRequestTimeout;
    }
    
    @NotEmpty
    @JsonProperty
    public String getAccessKey() {
      return accessKey;
    }

    @NotEmpty
    @JsonProperty
    public String getAccessSecret() {
      return accessSecret;
    }
    
    @Valid
    public Duration getTimeToLive() {
    return timeToLive;
    }
    
    @JsonProperty
    public int getScanPageSize() {
      return scanPageSize;
    }
    
    @JsonProperty
    public String getUserLoginTableName() {
      return userLoginTableName;
    }
    
    @JsonProperty
    public String getMiscTableName() {
      return miscTableName;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getAccountsTableName() {
      return accountsTableName;
    }
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getMessagesTableName() {
      return messagesTableName;
    }
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getKeysTableName() {
      return keysTableName;
    }
    
    @Valid
    @NotEmpty
    @JsonProperty
    public String getPushChallengeTableName() {
      return pushChallengeTableName;
    }
   
    @Valid
    @NotEmpty
    @JsonProperty
    public String getReportMessageTableName() {
      return reportMessageTableName;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getPendingAccountsTableName() {
      return pendingAccountsTableName;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getPendingDevicesTableName() {
      return pendingDevicesTableName;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getDeletedAccountsTableName() {
      return deletedAccountsTableName;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getGroupsTableName() {
      return groupsTableName;
    }

    @Valid
    @NotEmpty
    @JsonProperty
    public String getGroupLogsTableName() {
      return groupLogsTableName;
    }    
}