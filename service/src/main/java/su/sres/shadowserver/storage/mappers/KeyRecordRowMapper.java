/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage.mappers;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import su.sres.shadowserver.storage.KeyRecord;

import java.sql.ResultSet;
import java.sql.SQLException;

public class KeyRecordRowMapper implements RowMapper<KeyRecord> {

  @Override
  public KeyRecord map(ResultSet resultSet, StatementContext ctx) throws SQLException {
    return new KeyRecord(resultSet.getLong("id"),
                         resultSet.getString("number"),
                         resultSet.getLong("device_id"),
                         resultSet.getLong("key_id"),
                         resultSet.getString("public_key"));
  }
}