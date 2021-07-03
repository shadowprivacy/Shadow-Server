package su.sres.shadowserver.storage;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

public class RemoteConfig {

	@JsonProperty
	@Pattern(regexp = "[A-Za-z0-9\\.]+")
	private String name;

	@JsonProperty
	@NotNull
	@Min(0)
	@Max(100)
	private int percentage;

	@JsonProperty
	@NotNull
	private Set<UUID> uuids = new HashSet<>();

	@JsonProperty
	private String defaultValue;

	@JsonProperty
	private String value;

	public RemoteConfig() {
	}

	public RemoteConfig(String name, int percentage, Set<UUID> uuids, String defaultValue, String value) {
		this.name = name;
		this.percentage = percentage;
		this.uuids = uuids;
		this.defaultValue = defaultValue;
		this.value = value;
	}

	public int getPercentage() {
		return percentage;
	}

	public String getName() {
		return name;
	}

	public Set<UUID> getUuids() {
		return uuids;
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public String getValue() {
		return value;
	}
}