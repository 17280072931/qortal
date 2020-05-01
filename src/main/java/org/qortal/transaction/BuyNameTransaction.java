package org.qortal.transaction;

import java.util.Collections;
import java.util.List;

import org.qortal.account.Account;
import org.qortal.asset.Asset;
import org.qortal.crypto.Crypto;
import org.qortal.data.naming.NameData;
import org.qortal.data.transaction.BuyNameTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.naming.Name;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

import com.google.common.base.Utf8;

public class BuyNameTransaction extends Transaction {

	// Properties

	private BuyNameTransactionData buyNameTransactionData;

	// Constructors

	public BuyNameTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.buyNameTransactionData = (BuyNameTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<String> getRecipientAddresses() throws DataException {
		return Collections.singletonList(this.buyNameTransactionData.getSeller());
	}

	// Navigation

	public Account getBuyer() {
		return this.getCreator();
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		String name = this.buyNameTransactionData.getName();

		// Check seller address is valid
		if (!Crypto.isValidAddress(this.buyNameTransactionData.getSeller()))
			return ValidationResult.INVALID_ADDRESS;

		// Check name size bounds
		int nameLength = Utf8.encodedLength(name);
		if (nameLength < 1 || nameLength > Name.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check name is lowercase
		if (!name.equals(name.toLowerCase()))
			return ValidationResult.NAME_NOT_LOWER_CASE;

		NameData nameData = this.repository.getNameRepository().fromName(name);

		// Check name exists
		if (nameData == null)
			return ValidationResult.NAME_DOES_NOT_EXIST;

		// Check name is currently for sale
		if (!nameData.getIsForSale())
			return ValidationResult.NAME_NOT_FOR_SALE;

		// Check buyer isn't trying to buy own name
		Account buyer = getBuyer();
		if (buyer.getAddress().equals(nameData.getOwner()))
			return ValidationResult.BUYER_ALREADY_OWNER;

		// Check expected seller currently owns name
		if (!this.buyNameTransactionData.getSeller().equals(nameData.getOwner()))
			return ValidationResult.INVALID_SELLER;

		// Check amounts agree
		if (this.buyNameTransactionData.getAmount() != nameData.getSalePrice())
			return ValidationResult.INVALID_AMOUNT;

		// Check issuer has enough funds
		if (buyer.getConfirmedBalance(Asset.QORT) < this.buyNameTransactionData.getFee())
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Update Name
		Name name = new Name(this.repository, this.buyNameTransactionData.getName());
		name.buy(this.buyNameTransactionData);

		// Save transaction with updated "name reference" pointing to previous transaction that updated name
		this.repository.getTransactionRepository().save(this.buyNameTransactionData);
	}

	@Override
	public void orphan() throws DataException {
		// Revert name
		Name name = new Name(this.repository, this.buyNameTransactionData.getName());
		name.unbuy(this.buyNameTransactionData);

		// Save this transaction, with removed "name reference"
		this.repository.getTransactionRepository().save(this.buyNameTransactionData);
	}

}
