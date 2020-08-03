package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import org.qortal.crosschain.BTCACCT;
import org.qortal.data.crosschain.CrossChainTradeData;

import io.swagger.v3.oas.annotations.media.Schema;

// All properties to be converted to JSON via JAXB
@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainOfferSummary {

	// Properties

	@Schema(description = "AT's Qortal address")
	public String qortalAtAddress;

	@Schema(description = "AT creator's Qortal address")
	public String qortalCreator;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long qortAmount;

	@XmlJavaTypeAdapter(value = org.qortal.api.AmountTypeAdapter.class)
	private long btcAmount;

	@Schema(description = "Suggested trade timeout (minutes)", example = "10080")
	private int tradeTimeout;

	private BTCACCT.Mode mode;

	protected CrossChainOfferSummary() {
		/* For JAXB */
	}

	public CrossChainOfferSummary(CrossChainTradeData crossChainTradeData) {
		this.qortalAtAddress = crossChainTradeData.qortalAtAddress;
		this.qortalCreator = crossChainTradeData.qortalCreator;
		this.qortAmount = crossChainTradeData.qortAmount;
		this.btcAmount = crossChainTradeData.expectedBitcoin;
		this.tradeTimeout = crossChainTradeData.tradeTimeout;
		this.mode = crossChainTradeData.mode;
	}

	public String getQortalAtAddress() {
		return this.qortalAtAddress;
	}

	public String getQortalCreator() {
		return this.qortalCreator;
	}

	public long getQortAmount() {
		return this.qortAmount;
	}

	public long getBtcAmount() {
		return this.btcAmount;
	}

	public int getTradeTimeout() {
		return this.tradeTimeout;
	}

	public BTCACCT.Mode getMode() {
		return this.mode;
	}

}
