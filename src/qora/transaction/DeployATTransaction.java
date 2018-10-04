package qora.transaction;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Utf8;

import data.transaction.DeployATTransactionData;
import data.transaction.TransactionData;
import qora.account.Account;
import qora.assets.Asset;
import qora.at.AT;
import qora.block.BlockChain;
import qora.crypto.Crypto;
import repository.DataException;
import repository.Repository;
import transform.Transformer;

public class DeployATTransaction extends Transaction {

	// Properties
	private DeployATTransactionData deployATTransactionData;

	// Other useful constants
	public static final int MAX_NAME_SIZE = 200;
	public static final int MAX_DESCRIPTION_SIZE = 2000;
	public static final int MAX_AT_TYPE_SIZE = 200;
	public static final int MAX_TAGS_SIZE = 200;
	public static final int MAX_CREATION_BYTES_SIZE = 100_000;

	// Constructors

	public DeployATTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.deployATTransactionData = (DeployATTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return new ArrayList<Account>();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getCreator().getAddress()))
			return true;

		if (address.equals(this.getATAccount().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getCreator().getAddress()))
			amount = amount.subtract(this.deployATTransactionData.getAmount()).subtract(this.transactionData.getFee());

		if (address.equals(this.getATAccount().getAddress()))
			amount = amount.add(this.deployATTransactionData.getAmount());

		return amount;
	}

	/** Make sure deployATTransactionData has an ATAddress */
	private void ensureATAddress() throws DataException {
		if (this.deployATTransactionData.getATAddress() != null)
			return;

		int blockHeight = this.getHeight();
		if (blockHeight == 0)
			blockHeight = this.repository.getBlockRepository().getBlockchainHeight();

		try {
			byte[] name = this.deployATTransactionData.getName().getBytes("UTF-8");
			byte[] description = this.deployATTransactionData.getDescription().replaceAll("\\s", "").getBytes("UTF-8");
			byte[] creatorPublicKey = this.deployATTransactionData.getCreatorPublicKey();
			byte[] creationBytes = this.deployATTransactionData.getCreationBytes();

			ByteBuffer byteBuffer = ByteBuffer
					.allocate(name.length + description.length + creatorPublicKey.length + creationBytes.length + Transformer.INT_LENGTH);

			byteBuffer.order(ByteOrder.LITTLE_ENDIAN);

			byteBuffer.put(name);
			byteBuffer.put(description);
			byteBuffer.put(creatorPublicKey);
			byteBuffer.put(creationBytes);
			byteBuffer.putInt(blockHeight);

			String atAddress = Crypto.toATAddress(byteBuffer.array());

			this.deployATTransactionData.setATAddress(atAddress);
		} catch (UnsupportedEncodingException e) {
			throw new DataException("Unable to generate AT account from Deploy AT transaction data", e);
		}
	}

	// Navigation

	public Account getATAccount() throws DataException {
		ensureATAddress();

		return new Account(this.repository, this.deployATTransactionData.getATAddress());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		if (this.repository.getBlockRepository().getBlockchainHeight() < BlockChain.getATReleaseHeight())
			return ValidationResult.NOT_YET_RELEASED;

		// Check name size bounds
		int nameLength = Utf8.encodedLength(deployATTransactionData.getName());
		if (nameLength < 1 || nameLength > MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check description size bounds
		int descriptionlength = Utf8.encodedLength(deployATTransactionData.getDescription());
		if (descriptionlength < 1 || descriptionlength > MAX_DESCRIPTION_SIZE)
			return ValidationResult.INVALID_DESCRIPTION_LENGTH;

		// Check AT-type size bounds
		int ATTypeLength = Utf8.encodedLength(deployATTransactionData.getATType());
		if (ATTypeLength < 1 || ATTypeLength > MAX_AT_TYPE_SIZE)
			return ValidationResult.INVALID_AT_TYPE_LENGTH;

		// Check tags size bounds
		int tagsLength = Utf8.encodedLength(deployATTransactionData.getTags());
		if (tagsLength < 1 || tagsLength > MAX_TAGS_SIZE)
			return ValidationResult.INVALID_TAGS_LENGTH;

		// Check amount is positive
		if (deployATTransactionData.getAmount().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_AMOUNT;

		// Check fee is positive
		if (deployATTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		// Check reference is correct
		Account creator = getCreator();

		if (!Arrays.equals(creator.getLastReference(), deployATTransactionData.getReference()))
			return ValidationResult.INVALID_REFERENCE;

		// Check creator has enough funds
		BigDecimal minimumBalance = deployATTransactionData.getFee().add(deployATTransactionData.getAmount());
		if (creator.getConfirmedBalance(Asset.QORA).compareTo(minimumBalance) < 0)
			return ValidationResult.NO_BALANCE;

		// Check creation bytes are valid (for v3+)
		byte[] creationBytes = deployATTransactionData.getCreationBytes();
		short version = (short) (creationBytes[0] | (creationBytes[1] << 8)); // Little-endian

		if (version >= 3) {
			// Do actual validation
		} else {
			// Skip validation for old, dead ATs
		}

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		ensureATAddress();

		// Deploy AT, saving into repository
		AT at = new AT(this.repository, this.deployATTransactionData);
		at.deploy();

		// Save this transaction itself
		this.repository.getTransactionRepository().save(this.transactionData);

		// Update creator's balance
		Account creator = getCreator();
		creator.setConfirmedBalance(Asset.QORA, creator.getConfirmedBalance(Asset.QORA).subtract(deployATTransactionData.getAmount()));
		creator.setConfirmedBalance(Asset.QORA, creator.getConfirmedBalance(Asset.QORA).subtract(deployATTransactionData.getFee()));

		// Update creator's reference
		creator.setLastReference(deployATTransactionData.getSignature());
	}

	@Override
	public void orphan() throws DataException {
		// Delete AT from repository
		AT at = new AT(this.repository, this.deployATTransactionData);
		at.undeploy();

		// Delete this transaction itself
		this.repository.getTransactionRepository().delete(deployATTransactionData);

		// Update creator's balance
		Account creator = getCreator();
		creator.setConfirmedBalance(Asset.QORA, creator.getConfirmedBalance(Asset.QORA).add(deployATTransactionData.getAmount()));
		creator.setConfirmedBalance(Asset.QORA, creator.getConfirmedBalance(Asset.QORA).add(deployATTransactionData.getFee()));

		// Update creator's reference
		creator.setLastReference(deployATTransactionData.getReference());
	}

}
