package su.sres.shadowserver.entities;


import com.fasterxml.jackson.annotation.JsonProperty;

public class PreKeyCount {

  @JsonProperty
  private int count;

  public PreKeyCount(int count) {
    this.count = count;
  }

  public PreKeyCount() {}

  public int getCount() {
    return count;
  }
}
