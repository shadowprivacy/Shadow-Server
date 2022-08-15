/*
 * Original software: Copyright 2013-2020 Signal Messenger, LLC
 * Modified software: Copyright 2019-2022 Anton Alipov, sole trader
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package su.sres.shadowserver.entities;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

import org.hibernate.validator.constraints.Length;

import su.sres.shadowserver.storage.Device;
import su.sres.shadowserver.storage.Device.DeviceCapabilities;
import su.sres.shadowserver.storage.PaymentAddress;

public class AccountAttributes {

    @JsonProperty
    private String signalingKey;

    @JsonProperty
    private boolean fetchesMessages;

    @JsonProperty
    private int registrationId;

    @JsonProperty
    @Length(max = 204, message = "This field must be less than 50 characters")
    private String name;

    @JsonProperty
    private String pin;

    @JsonProperty
    private String registrationLock;

    @JsonProperty
    private byte[] unidentifiedAccessKey;

    @JsonProperty
    private boolean unrestrictedUnidentifiedAccess;

    @JsonProperty
    private List<PaymentAddress> payments;

    @JsonProperty
    private DeviceCapabilities capabilities;

    @JsonProperty
    private boolean discoverableByUserLogin = true;

    public AccountAttributes() {
    }

    @VisibleForTesting
    public AccountAttributes(String signalingKey, boolean fetchesMessages, int registrationId, String pin) {
	this(signalingKey, fetchesMessages, registrationId, null, pin, null, null, true, null);
    }

    @VisibleForTesting
    public AccountAttributes(String signalingKey, boolean fetchesMessages, int registrationId, String name, String pin, String registrationLock, List<PaymentAddress> payments, boolean discoverableByUserLogin, final DeviceCapabilities capabilities) {
	this.signalingKey = signalingKey;
	this.fetchesMessages = fetchesMessages;
	this.registrationId = registrationId;
	this.name = name;
	this.pin = pin;
	this.registrationLock = registrationLock;
	this.payments = payments;
	this.discoverableByUserLogin = discoverableByUserLogin;
	this.capabilities = capabilities;
    }

    public String getSignalingKey() {
	return signalingKey;
    }

    public boolean getFetchesMessages() {
	return fetchesMessages;
    }

    public int getRegistrationId() {
	return registrationId;
    }

    public String getName() {
	return name;
    }

    public String getPin() {
	return pin;
    }

    public String getRegistrationLock() {
	return registrationLock;
    }

    public byte[] getUnidentifiedAccessKey() {
	return unidentifiedAccessKey;
    }

    public boolean isUnrestrictedUnidentifiedAccess() {
	return unrestrictedUnidentifiedAccess;
    }

    public DeviceCapabilities getCapabilities() {
	return capabilities;
    }

    public List<PaymentAddress> getPayments() {
	return payments;
    }

    public boolean isDiscoverableByUserLogin() {
	return discoverableByUserLogin;
    }
}
