package org.qora.account;

import java.math.BigDecimal;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qora.block.Block;
import org.qora.block.BlockChain;
import org.qora.data.account.AccountBalanceData;
import org.qora.data.account.AccountData;
import org.qora.data.account.RewardShareData;
import org.qora.data.block.BlockData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.BlockRepository;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.transaction.Transaction;
import org.qora.utils.Base58;

public class Account {

	private static final Logger LOGGER = LogManager.getLogger(Account.class);

	public static final int ADDRESS_LENGTH = 25;
	public static final int FOUNDER_FLAG = 0x1;

	protected Repository repository;
	protected String address;

	protected Account() {
	}

	/** Construct Account business object using account's address */
	public Account(Repository repository, String address) {
		this.repository = repository;
		this.address = address;
	}

	// Simple getters / setters

	public String getAddress() {
		return this.address;
	}

	/**
	 * Build AccountData object using available account information.
	 * <p>
	 * For example, PublicKeyAccount might override and add public key info.
	 * 
	 * @return
	 */
	protected AccountData buildAccountData() {
		return new AccountData(this.address);
	}

	// Balance manipulations - assetId is 0 for QORA

	public BigDecimal getBalance(long assetId, int confirmations) throws DataException {
		// Simple case: we only need balance with 1 confirmation
		if (confirmations == 1)
			return this.getConfirmedBalance(assetId);

		/*
		 * For a balance with more confirmations work back from last block, undoing transactions involving this account, until we have processed required number
		 * of blocks.
		 */
		BlockRepository blockRepository = this.repository.getBlockRepository();
		BigDecimal balance = this.getConfirmedBalance(assetId);
		BlockData blockData = blockRepository.getLastBlock();

		// Note: "blockData.getHeight() > 1" to make sure we don't examine genesis block
		for (int i = 1; i < confirmations && blockData != null && blockData.getHeight() > 1; ++i) {
			Block block = new Block(this.repository, blockData);

			// CIYAM AT transactions should be fetched from repository so no special handling needed here
			for (Transaction transaction : block.getTransactions())
				if (transaction.isInvolved(this))
					balance = balance.subtract(transaction.getAmount(this));

			blockData = block.getParent();
		}

		// Return balance
		return balance;
	}

	public BigDecimal getConfirmedBalance(long assetId) throws DataException {
		AccountBalanceData accountBalanceData = this.repository.getAccountRepository().getBalance(this.address, assetId);
		if (accountBalanceData == null)
			return BigDecimal.ZERO.setScale(8);

		return accountBalanceData.getBalance();
	}

	public void setConfirmedBalance(long assetId, BigDecimal balance) throws DataException {
		// Safety feature!
		if (balance.compareTo(BigDecimal.ZERO) < 0) {
			String message = String.format("Refusing to set negative balance %s [assetId %d] for %s", balance.toPlainString(), assetId, this.address);
			LOGGER.error(message);
			throw new DataException(message);
		}

		// Can't have a balance without an account - make sure it exists!
		this.repository.getAccountRepository().ensureAccount(this.buildAccountData());

		AccountBalanceData accountBalanceData = new AccountBalanceData(this.address, assetId, balance);
		this.repository.getAccountRepository().save(accountBalanceData);

		LOGGER.trace(() -> String.format("%s balance now %s [assetId %s]", this.address, balance.toPlainString(), assetId));
	}

	public void deleteBalance(long assetId) throws DataException {
		this.repository.getAccountRepository().delete(this.address, assetId);
	}

	// Reference manipulations

	/**
	 * Fetch last reference for account.
	 * 
	 * @return byte[] reference, or null if no reference or account not found.
	 * @throws DataException
	 */
	public byte[] getLastReference() throws DataException {
		byte[] reference = this.repository.getAccountRepository().getLastReference(this.address);
		LOGGER.trace(() -> String.format("Last reference for %s is %s", this.address, reference == null ? "null" : Base58.encode(reference)));
		return reference;
	}

	/**
	 * Fetch last reference for account, considering unconfirmed transactions only, or return null.
	 * <p>
	 * NOTE: calls Transaction.getUnconfirmedTransactions which discards uncommitted
	 * repository changes.
	 * 
	 * @return byte[] reference, or null if no unconfirmed transactions for this account.
	 * @throws DataException
	 */
	public byte[] getUnconfirmedLastReference() throws DataException {
		// Newest unconfirmed transaction takes priority
		List<TransactionData> unconfirmedTransactions = Transaction.getUnconfirmedTransactions(repository);

		byte[] reference = null;

		for (TransactionData transactionData : unconfirmedTransactions) {
			String unconfirmedTransactionAddress = PublicKeyAccount.getAddress(transactionData.getCreatorPublicKey());

			if (unconfirmedTransactionAddress.equals(this.address))
				reference = transactionData.getSignature();
		}

		final byte[] loggingReference = reference;
		LOGGER.trace(() -> String.format("Last unconfirmed reference for %s is %s", this.address, loggingReference == null ? "null" : Base58.encode(loggingReference)));
		return reference;
	}

	/**
	 * Set last reference for account.
	 * 
	 * @param reference
	 *            -- null allowed
	 * @throws DataException
	 */
	public void setLastReference(byte[] reference) throws DataException {
		LOGGER.trace(() -> String.format("Setting last reference for %s to %s", this.address, Base58.encode(reference)));

		AccountData accountData = this.buildAccountData();
		accountData.setReference(reference);
		this.repository.getAccountRepository().setLastReference(accountData);
	}

	// Default groupID manipulations

	/** Returns account's default groupID or null if account doesn't exist. */
	public Integer getDefaultGroupId() throws DataException {
		return this.repository.getAccountRepository().getDefaultGroupId(this.address);
	}

	/**
	 * Sets account's default groupID and saves into repository.
	 * <p>
	 * Caller will need to call <tt>repository.saveChanges()</tt>.
	 * 
	 * @param defaultGroupId
	 * @throws DataException
	 */
	public void setDefaultGroupId(int defaultGroupId) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setDefaultGroupId(defaultGroupId);
		this.repository.getAccountRepository().setDefaultGroupId(accountData);

		LOGGER.trace(() -> String.format("Account %s defaultGroupId now %d", accountData.getAddress(), defaultGroupId));
	}

	// Account flags

	public Integer getFlags() throws DataException {
		return this.repository.getAccountRepository().getFlags(this.address);
	}

	public void setFlags(int flags) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setFlags(flags);
		this.repository.getAccountRepository().setFlags(accountData);
	}

	public static boolean isFounder(Integer flags) {
		return flags != null && (flags & FOUNDER_FLAG) != 0;
	}

	public boolean isFounder() throws DataException  {
		Integer flags = this.getFlags();
		return Account.isFounder(flags);
	}

	// Minting blocks

	public boolean canMint() throws DataException {
		Integer level = this.getLevel();
		if (level != null && level >= BlockChain.getInstance().getMinAccountLevelToMint())
			return true;

		if (this.isFounder())
			return true;

		return false;
	}

	public boolean canRewardShare() throws DataException {
		Integer level = this.getLevel();
		if (level != null && level >= BlockChain.getInstance().getMinAccountLevelToRewardShare())
			return true;

		if (this.isFounder())
			return true;

		return false;
	}

	// Account level

	/** Returns account's level (0+) or null if account not found in repository. */
	public Integer getLevel() throws DataException {
		return this.repository.getAccountRepository().getLevel(this.address);
	}

	public void setLevel(int level) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setLevel(level);
		this.repository.getAccountRepository().setLevel(accountData);
	}

	public void setInitialLevel(int level) throws DataException {
		AccountData accountData = this.buildAccountData();
		accountData.setLevel(level);
		accountData.setInitialLevel(level);
		this.repository.getAccountRepository().setInitialLevel(accountData);
	}

	/**
	 * Returns 'effective' minting level, or zero if account does not exist/cannot mint.
	 * <p>
	 * For founder accounts, this returns "founderEffectiveMintingLevel" from blockchain config.
	 * 
	 * @return 0+
	 * @throws DataException
	 */
	public int getEffectiveMintingLevel() throws DataException {
		if (this.isFounder())
			return BlockChain.getInstance().getFounderEffectiveMintingLevel();

		Integer level = this.getLevel();
		if (level == null)
			return 0;

		return level;
	}

	/**
	 * Returns 'effective' minting level, or zero if reward-share does not exist.
	 * <p>
	 * For founder accounts, this returns "founderEffectiveMintingLevel" from blockchain config.
	 * 
	 * @param repository
	 * @param rewardSharePublicKey
	 * @return 0+
	 * @throws DataException
	 */
	public static int getRewardShareEffectiveMintingLevel(Repository repository, byte[] rewardSharePublicKey) throws DataException {
		// Find actual minter and get their effective minting level
		RewardShareData rewardShareData = repository.getAccountRepository().getRewardShare(rewardSharePublicKey);
		if (rewardShareData == null)
			return 0;

		PublicKeyAccount rewardShareMinter = new PublicKeyAccount(repository, rewardShareData.getMinterPublicKey());
		return rewardShareMinter.getEffectiveMintingLevel();
	}

}
