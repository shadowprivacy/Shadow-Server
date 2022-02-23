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
