/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage.mappers;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.Accounts;
import su.sres.shadowserver.util.SystemMapper;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class AccountRowMapper implements RowMapper<Account> {

  private static ObjectMapper mapper = SystemMapper.getMapper();

  @Override
  public Account map(ResultSet resultSet, StatementContext ctx) throws SQLException {
    try {
      Account account = mapper.readValue(resultSet.getString(Accounts.DATA), Account.class);
      account.setUserLogin(resultSet.getString(Accounts.USER_LOGIN));      
      account.setUuid(UUID.fromString(resultSet.getString(Accounts.UID)));
      account.setVersion(resultSet.getInt(Accounts.VERSION));

      return account;
    } catch (IOException e) {
      throw new SQLException(e);
    }
  }
} 