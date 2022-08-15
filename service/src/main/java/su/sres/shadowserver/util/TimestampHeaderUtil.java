/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

public class TimestampHeaderUtil {

    public static final String TIMESTAMP_HEADER = "X-Signal-Timestamp";

    private TimestampHeaderUtil() {
    }

    public static String getTimestampHeader() {
        return TIMESTAMP_HEADER + ":" + System.currentTimeMillis();
    }
}
