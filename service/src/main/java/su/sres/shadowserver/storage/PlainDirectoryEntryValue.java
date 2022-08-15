/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.storage;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class PlainDirectoryEntryValue {

	@JsonProperty
	private UUID uuid;

	public PlainDirectoryEntryValue() {}
	
	public PlainDirectoryEntryValue(UUID uuid) {
		
		this.uuid = uuid;		
	}

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}	
}
