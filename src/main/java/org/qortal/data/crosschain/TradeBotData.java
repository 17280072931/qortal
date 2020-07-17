package org.qortal.data.crosschain;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlTransient;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class TradeBotData {

	public enum State {
		BOB_START(0), BOB_WAITING_FOR_P2SH_A(10), BOB_WAITING_FOR_P2SH_B(20), BOB_WAITING_FOR_AT_REDEEM(30),
		ALICE_START(100), ALICE_WAITING_FOR_P2SH_A(110), ALICE_WAITING_FOR_AT_LOCK(120), ALICE_WATCH_P2SH_B(130);

		public final int value;
		private static final Map<Integer, State> map = stream(State.values()).collect(toMap(state -> state.value, state -> state));

		State(int value) {
			this.value = value;
		}

		public static State valueOf(int value) {
			return map.get(value);
		}
	}

	private State tradeState;

	// Never expose this
	@XmlTransient
	@Schema(hidden = true)
	private byte[] tradePrivateKey;

	private byte[] secret;

	private String atAddress;

	private byte[] lastTransactionSignature;

	public TradeBotData(byte[] tradePrivateKey, State tradeState, byte[] secret, String atAddress,
			byte[] lastTransactionSignature) {
		this.tradePrivateKey = tradePrivateKey;
		this.tradeState = tradeState;
		this.secret = secret;
		this.atAddress = atAddress;
		this.lastTransactionSignature = lastTransactionSignature;
	}

	public TradeBotData(byte[] tradePrivateKey, State tradeState) {
		this.tradePrivateKey = tradePrivateKey;
		this.tradeState = tradeState;
	}

	public State getState() {
		return this.tradeState;
	}

	public void setState(State state) {
		this.tradeState = state;
	}

	public byte[] getSecret() {
		return this.secret;
	}

	public void setSecret(byte[] secret) {
		this.secret = secret;
	}

	public byte[] getTradePrivateKey() {
		return this.tradePrivateKey;
	}

	public String getAtAddress() {
		return this.atAddress;
	}

	public void setAtAddress(String atAddress) {
		this.atAddress = atAddress;
	}

	public byte[] getLastTransactionSignature() {
		return this.lastTransactionSignature;
	}

	public void setLastTransactionSignature(byte[] lastTransactionSignature) {
		this.lastTransactionSignature = lastTransactionSignature;
	}

}
