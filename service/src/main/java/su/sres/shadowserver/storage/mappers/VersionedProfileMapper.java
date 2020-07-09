package su.sres.shadowserver.storage.mappers;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import su.sres.shadowserver.storage.Profiles;
import su.sres.shadowserver.storage.VersionedProfile;

import java.sql.ResultSet;
import java.sql.SQLException;

public class VersionedProfileMapper implements RowMapper<VersionedProfile> {

  @Override
  public VersionedProfile map(ResultSet resultSet, StatementContext ctx) throws SQLException {
    return new VersionedProfile(resultSet.getString(Profiles.VERSION),
                                resultSet.getString(Profiles.NAME),
                                resultSet.getString(Profiles.AVATAR),
                                resultSet.getBytes(Profiles.COMMITMENT));
  }
} 
