/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.util.ua;

public class UnrecognizedUserAgentException extends Exception {

    public UnrecognizedUserAgentException() {
    }

    public UnrecognizedUserAgentException(final String message) {
	super(message);
    }

    public UnrecognizedUserAgentException(final Throwable cause) {
	super(cause);
    }
}
