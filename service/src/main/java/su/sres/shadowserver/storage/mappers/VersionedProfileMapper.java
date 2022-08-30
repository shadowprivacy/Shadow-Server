/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
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
    return new VersionedProfile(
        resultSet.getString(Profiles.VERSION),
        resultSet.getString(Profiles.NAME),
        resultSet.getString(Profiles.AVATAR),
        resultSet.getString(Profiles.ABOUT_EMOJI),
        resultSet.getString(Profiles.ABOUT),
        resultSet.getString(Profiles.PAYMENT_ADDRESS),
        resultSet.getBytes(Profiles.COMMITMENT));
  }
}
