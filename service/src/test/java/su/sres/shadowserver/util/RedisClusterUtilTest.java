/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import io.lettuce.core.cluster.SlotHash;
import junitparams.JUnitParamsRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(JUnitParamsRunner.class)
public class RedisClusterUtilTest {

    @Test
    public void testGetMinimalHashTag() {
	for (int slot = 0; slot < SlotHash.SLOT_COUNT; slot++) {
	    assertEquals(slot, SlotHash.getSlot(RedisClusterUtil.getMinimalHashTag(slot)));
	}
    }    
}
