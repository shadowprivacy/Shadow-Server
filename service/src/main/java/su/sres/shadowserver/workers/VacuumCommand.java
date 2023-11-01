/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.workers;

import net.sourceforge.argparse4j.inf.Namespace;
import su.sres.shadowserver.WhisperServerConfiguration;
import su.sres.shadowserver.configuration.DatabaseConfiguration;
import su.sres.shadowserver.storage.AbusiveHostRules;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.storage.FaultTolerantDatabase;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.dropwizard.cli.ConfiguredCommand;
import io.dropwizard.setup.Bootstrap;

public class VacuumCommand extends ConfiguredCommand<WhisperServerConfiguration> {

  private final Logger logger = LoggerFactory.getLogger(VacuumCommand.class);

  public VacuumCommand() {
    super("vacuum", "Vacuum Postgres Tables");
  }

  @Override
  protected void run(Bootstrap<WhisperServerConfiguration> bootstrap,
      Namespace namespace,
      WhisperServerConfiguration config)
      throws Exception {
    DatabaseConfiguration accountDbConfig = config.getAccountsDatabaseConfiguration();
    DatabaseConfiguration abuseDbConfig = config.getAbuseDatabaseConfiguration();    
    Jdbi accountJdbi = Jdbi.create(accountDbConfig.getUrl(), accountDbConfig.getUser(), accountDbConfig.getPassword());
    Jdbi abuseJdbi = Jdbi.create(abuseDbConfig.getUrl(), abuseDbConfig.getUser(), abuseDbConfig.getPassword());
    
    FaultTolerantDatabase accountDatabase = new FaultTolerantDatabase("account_database_vacuum", accountJdbi, accountDbConfig.getCircuitBreakerConfiguration());
    FaultTolerantDatabase abuseDatabase = new FaultTolerantDatabase("abuse_database_vacuum", abuseJdbi, abuseDbConfig.getCircuitBreakerConfiguration());
    
    Accounts accounts = new Accounts(accountDatabase);       
    AbusiveHostRules abusiveHostRules = new AbusiveHostRules(abuseDatabase);

    logger.info("Vacuuming accounts...");
    accounts.vacuum();
        
    logger.info("Vacuuming abusive host rules...");
    abusiveHostRules.vacuum();
    
    Thread.sleep(3000);
    System.exit(0);
  }
}
