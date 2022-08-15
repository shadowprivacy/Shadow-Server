/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.websocket.logging.layout.converters;

import su.sres.websocket.logging.WebsocketEvent;

public class ContentLengthConverter extends WebSocketEventConverter {
  @Override
  public String convert(WebsocketEvent event) {
    if (event.getContentLength() == WebsocketEvent.SENTINEL) {
      return WebsocketEvent.NA;
    } else {
      return Long.toString(event.getContentLength());
    }
  }
}
