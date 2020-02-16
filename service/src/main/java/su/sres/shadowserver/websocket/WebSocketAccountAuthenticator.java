package su.sres.shadowserver.websocket;

import org.eclipse.jetty.websocket.api.UpgradeRequest;
import su.sres.websocket.auth.WebSocketAuthenticator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.dropwizard.auth.basic.BasicCredentials;
import su.sres.shadowserver.auth.AccountAuthenticator;
import su.sres.shadowserver.storage.Account;

public class WebSocketAccountAuthenticator implements WebSocketAuthenticator<Account> {

	private final AccountAuthenticator accountAuthenticator;

	public WebSocketAccountAuthenticator(AccountAuthenticator accountAuthenticator) {
		this.accountAuthenticator = accountAuthenticator;
	}

	@Override
	public AuthenticationResult<Account> authenticate(UpgradeRequest request) {
		Map<String, List<String>> parameters = request.getParameterMap();
		List<String> usernames = parameters.get("login");
		List<String> passwords = parameters.get("password");

		if (usernames == null || usernames.size() == 0 || passwords == null || passwords.size() == 0) {
			return new AuthenticationResult<>(Optional.empty(), false);
		}

		BasicCredentials credentials = new BasicCredentials(usernames.get(0).replace(" ", "+"),
				passwords.get(0).replace(" ", "+"));

		return new AuthenticationResult<>(accountAuthenticator.authenticate(credentials), true);
	}

}
