package su.sres.shadowserver.controllers;

import com.codahale.metrics.annotation.Timed;
import su.sres.shadowserver.entities.AttachmentDescriptorV2;
import su.sres.shadowserver.limits.RateLimiter;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.s3.PolicySigner;
import su.sres.shadowserver.s3.PostPolicyGenerator;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.util.Pair;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import io.dropwizard.auth.Auth;

@Path("/v2/attachments")
public class AttachmentControllerV2 extends AttachmentControllerBase {

  private final PostPolicyGenerator policyGenerator;
  private final PolicySigner        policySigner;
  private final RateLimiter         rateLimiter;

  public AttachmentControllerV2(RateLimiters rateLimiters, String accessKey, String accessSecret, String region, String bucket) {
    this.rateLimiter      = rateLimiters.getAttachmentLimiter();
    this.policyGenerator  = new PostPolicyGenerator(region, bucket, accessKey);
    this.policySigner     = new PolicySigner(accessSecret, region);
  }

  @Timed
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/form/upload")
  public AttachmentDescriptorV2 getAttachmentUploadForm(@Auth Account account) throws RateLimitExceededException {
    rateLimiter.validate(account.getNumber());

    ZonedDateTime        now          = ZonedDateTime.now(ZoneOffset.UTC);
    long                 attachmentId = generateAttachmentId();
    String               objectName   = String.valueOf(attachmentId);
    Pair<String, String> policy       = policyGenerator.createFor(now, String.valueOf(objectName));
    String               signature    = policySigner.getSignature(now, policy.second());

    return new AttachmentDescriptorV2(attachmentId, objectName, policy.first(),
                                      "private", "AWS4-HMAC-SHA256",
                                      now.format(PostPolicyGenerator.AWS_DATE_TIME),
                                      policy.second(), signature);
  }
}