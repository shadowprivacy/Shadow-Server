/*
 * Copyright (C) 2020 Anton Alipov, sole trader
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
package su.sres.shadowserver.controllers;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.codahale.metrics.MetricRegistry.name;
import io.dropwizard.auth.Auth;
import io.dropwizard.jersey.protobuf.ProtocolBufferMediaType;
import su.sres.shadowserver.limits.RateLimiters;
import su.sres.shadowserver.storage.Account;
import su.sres.shadowserver.storage.AccountsManager;
import su.sres.shadowserver.storage.DirectoryManager;
import su.sres.shadowserver.storage.protos.DirectoryResponse;
import su.sres.shadowserver.storage.protos.DirectoryUpdate;
import su.sres.shadowserver.storage.protos.DirectoryUpdate.Type;
import su.sres.shadowserver.util.Constants;

import static su.sres.shadowserver.storage.DirectoryManager.INCREMENTAL_UPDATES_TO_HOLD;

@Path("/v1/dirplain")
public class PlainDirectoryController {

    private final Logger logger = LoggerFactory.getLogger(PlainDirectoryController.class);
//  private final MetricRegistry metricRegistry    = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
//  private final Histogram      contactsHistogram = metricRegistry.histogram(name(getClass(), "contacts"));

    private final RateLimiters rateLimiters;
    private final DirectoryManager directory;
    private final AccountsManager accountsManager;
    private final AtomicInteger directoryReadLock;

    public PlainDirectoryController(RateLimiters rateLimiters, AccountsManager accountsManager) {
	this.accountsManager = accountsManager;
	this.rateLimiters = rateLimiters;

	directory = accountsManager.getDirectoryManager();
	directoryReadLock = new AtomicInteger(0);
    }

    @Timed
    @GET
    @Path("/download/{version}")
    @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
    public DirectoryResponse downloadDirectory(@PathParam("version") String receivedVersion, @Auth Account account) throws RateLimitExceededException {
	rateLimiters.getDirectoryLimiter().validate(account.getUserLogin());

	long remoteVersion = Long.parseLong(receivedVersion);
	long localVersion = accountsManager.getDirectoryVersion();

	if (
	// if directory is write locked, return no-update, whatever the version
	accountsManager.getAccountCreationLock() ||
		accountsManager.getAccountRemovalLock() ||
		accountsManager.getDirectoryRestoreLock() ||
		// if the local version is same as remote, return no-update as well
		remoteVersion == localVersion) {

	    return noUpdateResponse(localVersion);
	}

	if (remoteVersion > localVersion) {

	    // this should not happen except when something is going seriously wrong
	    throw new WebApplicationException(500);
	}

	directoryReadLock.getAndIncrement();
	directory.setDirectoryReadLock();

	try {

	    if (remoteVersion == 0) {

		return fullDirectoryResponse(localVersion);

	    } else {

		long versionDiff = localVersion - remoteVersion;

		if (versionDiff > INCREMENTAL_UPDATES_TO_HOLD) {

		    return fullDirectoryResponse(localVersion);

		} else {

		    HashMap<String, String> incrementalUpdate = directory.retrieveIncrementalUpdate((int) versionDiff);

		    if (!incrementalUpdate.isEmpty()) {

			return DirectoryResponse.newBuilder()
				.setVersion(localVersion)
				.setDirectoryUpdate(buildIncrementalUpdate(incrementalUpdate))
				.build();
		    } else {

			return fullDirectoryResponse(localVersion);
		    }
		}

	    }
	} finally {

	    if (directoryReadLock.decrementAndGet() == 0)
		directory.releaseDirectoryReadLock();
	}
    }

    @Timed
    @GET
    @Path("/download/forcefull")
    @Produces(ProtocolBufferMediaType.APPLICATION_PROTOBUF)
    public DirectoryResponse downloadFullDirectory(@Auth Account account) throws RateLimitExceededException {
	rateLimiters.getDirectoryLimiter().validate(account.getUserLogin());

	long localVersion = accountsManager.getDirectoryVersion();

	if (
	// if directory is write locked, return no-update, whatever the version
	accountsManager.getAccountCreationLock() ||
		accountsManager.getAccountRemovalLock() ||
		accountsManager.getDirectoryRestoreLock()) {

	    return noUpdateResponse(localVersion);
	}

	directoryReadLock.getAndIncrement();
	directory.setDirectoryReadLock();

	try {
	    return fullDirectoryResponse(localVersion);
	} finally {
	    if (directoryReadLock.decrementAndGet() == 0)
		directory.releaseDirectoryReadLock();
	}
    }

    private DirectoryResponse fullDirectoryResponse(long version) {

	if (!accountsManager.getDirectoryRestoreLock()) {

	    return DirectoryResponse.newBuilder()
		    .setVersion(version)
		    .setDirectoryUpdate(getFullDirectory())
		    .build();
	} else {

	    // if directory restore is currently in progress, we simply return no-update in
	    // order to avoid a possible race condition
	    return noUpdateResponse(version);
	}
    }

    private DirectoryResponse noUpdateResponse(long version) {

	return DirectoryResponse.newBuilder()
		.setVersion(version)
		.setIsUpdate(false)
		.build();
    }

    private DirectoryUpdate getFullDirectory() {

	HashMap<String, String> retrievedPlainDirectory = directory.retrievePlainDirectory();

	if (!retrievedPlainDirectory.isEmpty()) {

	    return DirectoryUpdate.newBuilder()
		    .setType(Type.FULL)
		    .putAllDirectoryEntry(retrievedPlainDirectory)
		    .build();

	} else {

	    // plain directory should never be empty; if it's not the case then something is
	    // wrong with Redis and we need to recreate it from SQL

	    // getFullDirectory() should not be invoked while the directory restoration lock
	    // is set, so there should be no race condition here
	    accountsManager.restorePlainDirectory();

	    return DirectoryUpdate.newBuilder()
		    .setType(Type.FULL)
		    .putAllDirectoryEntry(directory.retrievePlainDirectory())
		    .build();
	}
    }

    private DirectoryUpdate buildIncrementalUpdate(HashMap<String, String> incrementalUpdate) {

	return DirectoryUpdate.newBuilder()
		.setType(Type.INCREMENTAL)
		.putAllDirectoryEntry(incrementalUpdate)
		.build();
    }
}
