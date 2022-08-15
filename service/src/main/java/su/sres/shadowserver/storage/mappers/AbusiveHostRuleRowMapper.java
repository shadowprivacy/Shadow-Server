/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage.mappers;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import su.sres.shadowserver.storage.AbusiveHostRule;
import su.sres.shadowserver.storage.AbusiveHostRules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;


public class AbusiveHostRuleRowMapper implements RowMapper<AbusiveHostRule> {
  @Override
  public AbusiveHostRule map(ResultSet resultSet, StatementContext ctx) throws SQLException {
    String regionsData = resultSet.getString(AbusiveHostRules.REGIONS);

    List<String> regions;

    if (regionsData == null) regions = new LinkedList<>();
    else                     regions = Arrays.asList(regionsData.split(","));


    return new AbusiveHostRule(resultSet.getString(AbusiveHostRules.HOST), resultSet.getInt(AbusiveHostRules.BLOCKED) == 1, regions);
  }
}