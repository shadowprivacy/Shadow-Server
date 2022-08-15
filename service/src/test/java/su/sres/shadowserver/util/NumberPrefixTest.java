/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import org.junit.Test;
import su.sres.shadowserver.util.Util;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class NumberPrefixTest {

  @Test
  public void testPrefixes() {
    assertThat(Util.getNumberPrefix("+14151234567")).isEqualTo("+14151");
    assertThat(Util.getNumberPrefix("+22587654321")).isEqualTo("+2258765");
    assertThat(Util.getNumberPrefix("+298654321")).isEqualTo("+2986543");
    assertThat(Util.getNumberPrefix("+12")).isEqualTo("+12");
  }

}