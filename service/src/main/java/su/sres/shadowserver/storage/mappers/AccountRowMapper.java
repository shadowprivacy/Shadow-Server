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

public class AccountRowMapper implements RowMapper<Account> {

  private static ObjectMapper mapper = SystemMapper.getMapper();

  @Override
  public Account map(ResultSet resultSet, StatementContext ctx) throws SQLException {
    try {
      Account account = mapper.readValue(resultSet.getString(Accounts.DATA), Account.class);
      account.setNumber(resultSet.getString(Accounts.NUMBER));

      return account;
    } catch (IOException e) {
      throw new SQLException(e);
    }
  }
} 