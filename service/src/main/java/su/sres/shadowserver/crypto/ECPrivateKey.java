package su.sres.shadowserver.crypto;

public interface ECPrivateKey {
  public byte[] serialize();
  public int getType();
}