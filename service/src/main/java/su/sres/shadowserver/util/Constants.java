/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util;

import io.dropwizard.util.DataSize;

public class Constants {

    public static final String METRICS_NAME = "shadow";
    public static final int MAXIMUM_STICKER_SIZE_BYTES = (int) DataSize.kibibytes(300).toBytes();
    public static final int MAXIMUM_STICKER_MANIFEST_SIZE_BYTES = (int) DataSize.kibibytes(10).toBytes();
    public static final String serverLicenseFilename = "shadowserver.bin";

}
