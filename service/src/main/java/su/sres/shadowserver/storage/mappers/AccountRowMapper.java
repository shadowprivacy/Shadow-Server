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
      account.setNumber(resultSet.getString(Accounts.NUMBER));
 
   // circumventing the case when uuid is initially null for existing accounts
      
 //     if (resultSet.getString(Accounts.UID) !=null ) {
      
      account.setUuid(UUID.fromString(resultSet.getString(Accounts.UID)));
//      } else {
//    	  account.setUuid(null);
//      }

      return account;
    } catch (IOException e) {
      throw new SQLException(e);
    }
  }
} 