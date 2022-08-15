
/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */package su.sres.websocket.logging.layout;

import su.sres.websocket.logging.WebsocketEvent;

import java.util.TimeZone;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.pattern.PatternLayoutBase;
import io.dropwizard.logging.layout.LayoutFactory;

public class WebsocketEventLayoutFactory implements LayoutFactory<WebsocketEvent> {
  @Override
  public PatternLayoutBase<WebsocketEvent> build(LoggerContext context, TimeZone timeZone) {
    return new WebsocketEventLayout(context);
  }
}
