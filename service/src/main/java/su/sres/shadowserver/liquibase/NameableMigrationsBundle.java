/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.liquibase;

import io.dropwizard.Bundle;
import io.dropwizard.Configuration;
import io.dropwizard.db.DatabaseConfiguration;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Generics;

public abstract class NameableMigrationsBundle<T extends Configuration> implements Bundle, DatabaseConfiguration<T> {

  private final String name;
  private final String migrations;

  public NameableMigrationsBundle(String name, String migrations) {
    this.name       = name;
    this.migrations = migrations;
  }

  public final void initialize(Bootstrap<?> bootstrap) {
    Class klass = Generics.getTypeParameter(this.getClass(), Configuration.class);
    bootstrap.addCommand(new NameableDbCommand(name, migrations, this, klass));
  }

  public final void run(Environment environment) {
  }
}