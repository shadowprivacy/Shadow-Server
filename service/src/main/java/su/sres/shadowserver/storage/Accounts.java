/*
 * Copyright (C) 2013 Open WhisperSystems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.shadowserver.storage;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import su.sres.shadowserver.util.SystemMapper;
import su.sres.shadowserver.workers.VacuumCommand;
import su.sres.shadowserver.storage.mappers.AccountRowMapper;
import su.sres.shadowserver.util.Constants;

import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.codahale.metrics.MetricRegistry.name;

public class Accounts {

	private final Logger logger = LoggerFactory.getLogger(Accounts.class);

	public static final String ID         = "id";
	public static final String UID        = "uuid";
	public static final String USER_LOGIN = "number";
	public static final String DATA       = "data";
	public static final String DIR_VER    = "directory_version";

	private static final ObjectMapper mapper = SystemMapper.getMapper();

	private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
	private final Timer createTimer = metricRegistry.timer(name(Accounts.class, "create"));
	private final Timer updateTimer = metricRegistry.timer(name(Accounts.class, "update"));
	private final Timer getByUserLoginTimer = metricRegistry.timer(name(Accounts.class, "getByUserLogin"));
	private final Timer getByUuidTimer = metricRegistry.timer(name(Accounts.class, "getByUuid"));
	private final Timer getAllFromTimer = metricRegistry.timer(name(Accounts.class, "getAllFrom"));
	private final Timer getAllFromOffsetTimer = metricRegistry.timer(name(Accounts.class, "getAllFromOffset"));
	private final Timer vacuumTimer = metricRegistry.timer(name(Accounts.class, "vacuum"));

	// for DirectoryUpdater and directory restore
	private final Timer getAllTimer = metricRegistry.timer(name(Accounts.class, "getAll"));

	private final FaultTolerantDatabase database;

	public Accounts(FaultTolerantDatabase database) {
		this.database = database;
		this.database.getDatabase().registerRowMapper(new AccountRowMapper());
	}

	public boolean create(Account account, long directoryVersion) {
		return database.with(jdbi -> jdbi.inTransaction(TransactionIsolationLevel.SERIALIZABLE, handle -> {
			try (Timer.Context ignored = createTimer.time()) {

                // insert the account into the database and return the uuid; if the number already exists, just update data; ultimately if the "old" uuid differs means that the account is not new, and the new random uuid is reset to the old one  
                // TODO: if directory holds more than just usernames in future we shall need to update the directory version as well. 	    	  
				UUID uuid = handle.createQuery("INSERT INTO accounts (" + USER_LOGIN + ", " + UID + ", " + DATA + ", " + DIR_VER + ") VALUES (:number, :uuid, CAST(:data AS json), :directory_version) ON CONFLICT(number) DO UPDATE SET data = EXCLUDED.data RETURNING uuid")
						          .bind("number", account.getUserLogin())
						          .bind("uuid", account.getUuid())
						          .bind("data", mapper.writeValueAsString(account))						          
						          .bind("directory_version", (directoryVersion))
						          .mapTo(UUID.class)
						          .findOnly();

				boolean isNew;
				isNew = uuid.equals(account.getUuid());

				account.setUuid(uuid);
				return isNew;

			} catch (JsonProcessingException e) {
				throw new IllegalArgumentException(e);
			}
		}));
	}

	public void update(Account account) {
		database.use(jdbi -> jdbi.useHandle(handle -> {
			try (Timer.Context ignored = updateTimer.time()) {
				handle.createUpdate("UPDATE accounts SET " + DATA + " = CAST(:data AS json) WHERE " + UID + " = :uuid")
					  .bind("uuid", account.getUuid())
					  .bind("data", mapper.writeValueAsString(account))
					  .execute();

			} catch (JsonProcessingException e) {
				throw new IllegalArgumentException(e);
			}
		}));
	}

	public void update(Account account, boolean isRemoval, long directoryVersion) {
		database.use(jdbi -> jdbi.useHandle(handle -> {
			try (Timer.Context ignored = updateTimer.time()) {
				handle.createUpdate("UPDATE accounts SET " + DATA + " = CAST(:data AS json) WHERE " + UID + " = :uuid")
				      .bind("uuid", account.getUuid())
				      .bind("data", mapper.writeValueAsString(account))
				      .execute();

				// TODO: currently we increment directory version only on removal; if directory holds more than just usernames in future, will need to increment on any update
				if (isRemoval) {
					handle.createUpdate("UPDATE accounts SET " + DIR_VER + " = :directory_version WHERE " + UID + " = :uuid")
				          .bind("directory_version", directoryVersion)
				          .bind("uuid", account.getUuid())
				          .execute();
				}

			} catch (JsonProcessingException e) {
				throw new IllegalArgumentException(e);
			}
		}));
	}

	public Optional<Account> get(String userLogin) {
		return database.with(jdbi -> jdbi.withHandle(handle -> {
			try (Timer.Context ignored = getByUserLoginTimer.time()) {
				return handle.createQuery("SELECT * FROM accounts WHERE " + USER_LOGIN + " = :number")
						     .bind("number", userLogin)
						     .mapTo(Account.class)
						     .findFirst();
			}
		}));
	}

	public Optional<Account> get(UUID uuid) {
		return database.with(jdbi -> jdbi.withHandle(handle -> {
			try (Timer.Context ignored = getByUuidTimer.time()) {
				return handle.createQuery("SELECT * FROM accounts WHERE " + UID + " = :uuid")
						     .bind("uuid", uuid)
						     .mapTo(Account.class)
						     .findFirst();
			}
		}));
	}

	public List<Account> getAllFrom(UUID from, int length) {
		return database.with(jdbi -> jdbi.withHandle(handle -> {
			try (Timer.Context ignored = getAllFromOffsetTimer.time()) {
				return handle.createQuery("SELECT * FROM accounts WHERE " + UID + " > :from ORDER BY " + UID + " LIMIT :limit")
			 			     .bind("from", from)
			 			     .bind("limit", length)
			 			     .mapTo(Account.class)
			 			     .list();
			}
		}));
	}

	public List<Account> getAllFrom(int length) {
		return database.with(jdbi -> jdbi.withHandle(handle -> {
			try (Timer.Context ignored = getAllFromTimer.time()) {
				return handle.createQuery("SELECT * FROM accounts ORDER BY " + UID + " LIMIT :limit")
						     .bind("limit", length)
						     .mapTo(Account.class)
						     .list();
			}
		}));
	}

	// this is used by directory restore and DirectoryUpdater
	public List<Account> getAll(int offset, int length) {
		return database.with(jdbi -> jdbi.withHandle(handle -> {
			try (Timer.Context ignored = getAllTimer.time()) {

				return handle.createQuery("SELECT * FROM accounts OFFSET :offset LIMIT :limit").bind("offset", offset)
						     .bind("limit", length)
						     .mapTo(Account.class)
						     .list();
			}
		}));
	}

	public void vacuum() {
		database.use(jdbi -> jdbi.useHandle(handle -> {
			try (Timer.Context ignored = vacuumTimer.time()) {
				handle.execute("VACUUM accounts");
			}
		}));
	}

	public Long restoreDirectoryVersion() {
		return database.with(jdbi -> jdbi.withHandle(handle -> {
			return handle.createQuery("SELECT COALESCE(MAX(directory_version),0) FROM accounts")
					     .mapTo(Long.class)
					     .findOnly();

		}));
	}
}
