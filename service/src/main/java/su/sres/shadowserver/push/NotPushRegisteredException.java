package su.sres.shadowserver.push;

public class NotPushRegisteredException extends Exception {
  public NotPushRegisteredException(String s) {
    super(s);
  }

  public NotPushRegisteredException(Exception e) {
    super(e);
  }
}
