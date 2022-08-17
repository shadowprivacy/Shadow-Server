/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.auth;

import java.util.List;
import java.util.Optional;

import io.dropwizard.auth.Authenticator;
import io.dropwizard.auth.basic.BasicCredentials;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tag;

import static com.codahale.metrics.MetricRegistry.name;

import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;

public class AccountAuthenticator extends BaseAccountAuthenticator implements Authenticator<BasicCredentials, Account> {

    private static final String AUTHENTICATION_COUNTER_NAME = name(AccountAuthenticator.class, "authenticate");
    private static final String GV2_CAPABLE_TAG_NAME = "gv1Migration";

    public AccountAuthenticator(AccountsManager accountsManager) {
	super(accountsManager);
    }

    @Override
    public Optional<Account> authenticate(BasicCredentials basicCredentials) {
	final Optional<Account> maybeAccount = super.authenticate(basicCredentials, true);

	// TODO Remove this temporary counter when we can replace it with more generic
	// feature adoption system
	maybeAccount.ifPresent(account -> {
	    Metrics.counter(AUTHENTICATION_COUNTER_NAME, GV2_CAPABLE_TAG_NAME, String.valueOf(account.isGv1MigrationSupported())).increment();
	});

	return maybeAccount;
    }
}