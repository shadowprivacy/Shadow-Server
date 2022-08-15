/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import org.junit.Test;

import su.sres.shadowserver.util.BlockingThreadPoolExecutor;
import su.sres.shadowserver.util.Util;

import static org.junit.Assert.assertTrue;

public class BlockingThreadPoolExecutorTest {

    @Test
    public void testBlocking() {
	BlockingThreadPoolExecutor executor = new BlockingThreadPoolExecutor("test", 1, 3);
	long start = System.currentTimeMillis();

	executor.execute(new Runnable() {
	    @Override
	    public void run() {
		Util.sleep(1000);
	    }
	});

	assertTrue(System.currentTimeMillis() - start < 500);
	start = System.currentTimeMillis();

	executor.execute(new Runnable() {
	    @Override
	    public void run() {
		Util.sleep(1000);
	    }
	});

	assertTrue(System.currentTimeMillis() - start < 500);

	start = System.currentTimeMillis();

	executor.execute(new Runnable() {
	    @Override
	    public void run() {
		Util.sleep(1000);
	    }
	});

	assertTrue(System.currentTimeMillis() - start < 500);

	start = System.currentTimeMillis();

	executor.execute(new Runnable() {
	    @Override
	    public void run() {
		Util.sleep(1000);
	    }
	});

	assertTrue(System.currentTimeMillis() - start > 500);
    }

}
