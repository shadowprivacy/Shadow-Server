package su.sres.shadowserver.controllers;

import su.sres.shadowserver.util.Conversions;

import java.security.SecureRandom;

public class AttachmentControllerBase {

  protected long generateAttachmentId() {
    byte[] attachmentBytes = new byte[8];
    new SecureRandom().nextBytes(attachmentBytes);

    attachmentBytes[0] = (byte)(attachmentBytes[0] & 0x7F);
    return Conversions.byteArrayToLong(attachmentBytes);
  }
}