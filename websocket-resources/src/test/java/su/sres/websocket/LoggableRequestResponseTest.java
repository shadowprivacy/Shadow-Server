package su.sres.websocket;

import org.eclipse.jetty.server.AbstractNCSARequestLog;
import org.eclipse.jetty.server.NCSARequestLog;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.junit.Test;
import su.sres.websocket.messages.WebSocketMessageFactory;
import su.sres.websocket.messages.WebSocketRequestMessage;
import su.sres.websocket.servlet.LoggableRequest;
import su.sres.websocket.servlet.LoggableResponse;
import su.sres.websocket.servlet.WebSocketServletRequest;
import su.sres.websocket.servlet.WebSocketServletResponse;
import su.sres.websocket.session.WebSocketSessionContext;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.HashMap;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LoggableRequestResponseTest {

  @Test
  public void testLogging() {
    NCSARequestLog requestLog = new EnabledNCSARequestLog();

    WebSocketClient         webSocketClient = mock(WebSocketClient.class        );
    WebSocketRequestMessage requestMessage  = mock(WebSocketRequestMessage.class);
    ServletContext          servletContext  = mock(ServletContext.class         );
    RemoteEndpoint          remoteEndpoint  = mock(RemoteEndpoint.class         );
    WebSocketMessageFactory messageFactory  = mock(WebSocketMessageFactory.class);

    when(requestMessage.getVerb()).thenReturn("GET");
    when(requestMessage.getBody()).thenReturn(Optional.empty());
    when(requestMessage.getHeaders()).thenReturn(new HashMap<>());
    when(requestMessage.getPath()).thenReturn("/api/v1/test");
    when(requestMessage.getRequestId()).thenReturn(1L);
    when(requestMessage.hasRequestId()).thenReturn(true);

    WebSocketSessionContext sessionContext  = new WebSocketSessionContext (webSocketClient                               );
    HttpServletRequest      servletRequest  = new WebSocketServletRequest (sessionContext, requestMessage, servletContext);
    HttpServletResponse     servletResponse = new WebSocketServletResponse(remoteEndpoint, 1, messageFactory             );

    LoggableRequest  loggableRequest  = new LoggableRequest (servletRequest );
    LoggableResponse loggableResponse = new LoggableResponse(servletResponse);

    requestLog.log(loggableRequest, loggableResponse);
  }


  private class EnabledNCSARequestLog extends NCSARequestLog {
    @Override
    public boolean isEnabled() {
      return true;
    }
  }

}
