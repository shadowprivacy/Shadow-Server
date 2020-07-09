package su.sres.websocket.logging.layout.converters;

import su.sres.websocket.logging.WebsocketEvent;

public class NAConverter extends WebSocketEventConverter {
  @Override
  public String convert(WebsocketEvent event) {
    return WebsocketEvent.NA;
  }
}