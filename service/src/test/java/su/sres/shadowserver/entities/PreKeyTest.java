/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static su.sres.shadowserver.util.JsonHelpers.*;

public class PreKeyTest {

  @Test
  public void serializeToJSONV2() throws Exception {
    PreKey preKey = new PreKey(1234, "test");

    assertThat("PreKeyV2 Serialization works",
        asJson(preKey),
        is(equalTo(jsonFixture("fixtures/prekey_v2.json"))));
  }

}
