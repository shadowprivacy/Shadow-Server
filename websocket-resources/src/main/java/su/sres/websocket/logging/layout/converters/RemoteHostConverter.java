package su.sres.websocket.logging.layout.converters;

import su.sres.websocket.logging.WebsocketEvent;

public class RemoteHostConverter extends WebSocketEventConverter {
  @Override
  public String convert(WebsocketEvent event) {
    return event.getRemoteHost();
  }
}
