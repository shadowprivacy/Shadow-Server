package su.sres.shadowserver.configuration.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

public class DynamicAccountsScyllaDbMigrationConfiguration {
  @JsonProperty
  boolean scyllaPrimary = false;
  
  @JsonProperty
  boolean backgroundMigrationEnabled = true;

  @JsonProperty
  int backgroundMigrationExecutorThreads = 1;

  @JsonProperty
  boolean deleteEnabled = true;

  @JsonProperty
  boolean writeEnabled = true;

  @JsonProperty
  boolean readEnabled = true;
  
  @JsonProperty
  boolean postCheckMismatches = true;

  @JsonProperty
  boolean logMismatches = true;

  @JsonProperty
  boolean crawlerPreReadNextChunkEnabled = false;

  @JsonProperty
  boolean scyllaCrawlerEnabled = true;

  @JsonProperty
  int scyllaCrawlerScanPageSize = 10;

  public boolean isBackgroundMigrationEnabled() {
    return backgroundMigrationEnabled;
  }
  
  public boolean isScyllaPrimary() {
    return scyllaPrimary;
  }

  public int getBackgroundMigrationExecutorThreads() {
    return backgroundMigrationExecutorThreads;
  }

  @VisibleForTesting
  public void setBackgroundMigrationEnabled(boolean backgroundMigrationEnabled) {
    this.backgroundMigrationEnabled = backgroundMigrationEnabled;
  }

  public void setDeleteEnabled(boolean deleteEnabled) {
    this.deleteEnabled = deleteEnabled;
  }

  public boolean isDeleteEnabled() {
    return deleteEnabled;
  }

  public void setWriteEnabled(boolean writeEnabled) {
    this.writeEnabled = writeEnabled;
  }

  public boolean isWriteEnabled() {
    return writeEnabled;
  }

  @VisibleForTesting
  public void setReadEnabled(boolean readEnabled) {
    this.readEnabled = readEnabled;
  }

  public boolean isReadEnabled() {
    return readEnabled;
  }
  
  public boolean isPostCheckMismatches() {
    return postCheckMismatches;
  }

  public boolean isLogMismatches() {
    return logMismatches;
  }

  public boolean isCrawlerPreReadNextChunkEnabled() {
    return crawlerPreReadNextChunkEnabled;
  }

  public boolean isScyllaCrawlerEnabled() {
    return scyllaCrawlerEnabled;
  }

  public int getScyllaCrawlerScanPageSize() {
    return scyllaCrawlerScanPageSize;
  }

  @VisibleForTesting
  public void setLogMismatches(boolean logMismatches) {
    this.logMismatches = logMismatches;
  }

  @VisibleForTesting
  public void setBackgroundMigrationExecutorThreads(int threads) {
    this.backgroundMigrationExecutorThreads = threads;
  }
}
