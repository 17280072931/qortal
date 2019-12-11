package org.qora.repository.hsqldb;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.qora.data.account.AccountBalanceData;
import org.qora.data.account.AccountData;
import org.qora.data.account.MintingAccountData;
import org.qora.data.account.QortFromQoraData;
import org.qora.data.account.RewardShareData;
import org.qora.repository.AccountRepository;
import org.qora.repository.DataException;

public class HSQLDBAccountRepository implements AccountRepository {

	protected HSQLDBRepository repository;

	public HSQLDBAccountRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	// General account

	@Override
	public AccountData getAccount(String address) throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, initial_level, level, blocks_minted FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			byte[] reference = resultSet.getBytes(1);
			byte[] publicKey = resultSet.getBytes(2);
			int defaultGroupId = resultSet.getInt(3);
			int flags = resultSet.getInt(4);
			int initialLevel = resultSet.getInt(5);
			int level = resultSet.getInt(6);
			int blocksMinted = resultSet.getInt(7);

			return new AccountData(address, reference, publicKey, defaultGroupId, flags, initialLevel, level, blocksMinted);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account info from repository", e);
		}
	}

	@Override
	public List<AccountData> getFlaggedAccounts(int mask) throws DataException {
		String sql = "SELECT reference, public_key, default_group_id, flags, initial_level, level, blocks_minted, account FROM Accounts WHERE BITAND(flags, ?) != 0";

		List<AccountData> accounts = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, mask)) {
			if (resultSet == null)
				return accounts;

			do {
				byte[] reference = resultSet.getBytes(1);
				byte[] publicKey = resultSet.getBytes(2);
				int defaultGroupId = resultSet.getInt(3);
				int flags = resultSet.getInt(4);
				int initialLevel = resultSet.getInt(5);
				int level = resultSet.getInt(6);
				int blocksMinted = resultSet.getInt(7);
				String address = resultSet.getString(8);

				accounts.add(new AccountData(address, reference, publicKey, defaultGroupId, flags, initialLevel, level, blocksMinted));
			} while (resultSet.next());

			return accounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch flagged accounts from repository", e);
		}
	}

	@Override
	public byte[] getLastReference(String address) throws DataException {
		String sql = "SELECT reference FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getBytes(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's last reference from repository", e);
		}
	}

	@Override
	public Integer getDefaultGroupId(String address) throws DataException {
		String sql = "SELECT default_group_id FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			// Column is NOT NULL so this should never implicitly convert to 0
			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's default groupID from repository", e);
		}
	}

	@Override
	public Integer getFlags(String address) throws DataException {
		String sql = "SELECT flags FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's flags from repository", e);
		}
	}

	@Override
	public Integer getLevel(String address) throws DataException {
		String sql = "SELECT level FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account's level from repository", e);
		}
	}

	@Override
	public boolean accountExists(String address) throws DataException {
		try {
			return this.repository.exists("Accounts", "account = ?", address);
		} catch (SQLException e) {
			throw new DataException("Unable to check for account in repository", e);
		}
	}

	@Override
	public void ensureAccount(AccountData accountData) throws DataException {
		byte[] publicKey = accountData.getPublicKey();
		String sql = "SELECT public_key FROM Accounts WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, accountData.getAddress())) {
			if (resultSet != null) {
				// We know account record exists at this point.
				// If accountData has no public key then we're done.
				// If accountData's public key matches repository's public key then we're done.
				if (publicKey == null || Arrays.equals(resultSet.getBytes(1), publicKey))
					return;
			}

			// No record exists, or we have a public key to set
			HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

			saveHelper.bind("account", accountData.getAddress());

			if (publicKey != null)
				saveHelper.bind("public_key", publicKey);

			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to ensure minimal account in repository", e);
		}
	}

	@Override
	public void setLastReference(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("reference", accountData.getReference());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's last reference into repository", e);
		}
	}

	@Override
	public void setDefaultGroupId(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("default_group_id", accountData.getDefaultGroupId());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's default group ID into repository", e);
		}
	}

	@Override
	public void setFlags(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("flags", accountData.getFlags());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's flags into repository", e);
		}
	}

	@Override
	public void setLevel(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("level", accountData.getLevel());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's level into repository", e);
		}
	}

	@Override
	public void setInitialLevel(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("level", accountData.getLevel())
		.bind("initial_level", accountData.getInitialLevel());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's initial level into repository", e);
		}
	}

	@Override
	public void setMintedBlockCount(AccountData accountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Accounts");

		saveHelper.bind("account", accountData.getAddress()).bind("blocks_minted", accountData.getBlocksMinted());

		byte[] publicKey = accountData.getPublicKey();
		if (publicKey != null)
			saveHelper.bind("public_key", publicKey);

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account's minted block count into repository", e);
		}
	}

	@Override
	public void delete(String address) throws DataException {
		// NOTE: Account balances are deleted automatically by the database thanks to "ON DELETE CASCADE" in AccountBalances' FOREIGN KEY
		// definition.
		try {
			this.repository.delete("Accounts", "account = ?", address);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account from repository", e);
		}
	}

	// Account balances

	@Override
	public AccountBalanceData getBalance(String address, long assetId) throws DataException {
		String sql = "SELECT IFNULL(balance, 0) FROM AccountBalances WHERE account = ? AND asset_id = ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address, assetId)) {
			if (resultSet == null)
				return null;

			BigDecimal balance = resultSet.getBigDecimal(1).setScale(8);

			return new AccountBalanceData(address, assetId, balance);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account balance from repository", e);
		}
	}

	@Override
	public AccountBalanceData getBalance(String address, long assetId, int height) throws DataException {
		String sql = "SELECT IFNULL(balance, 0) FROM HistoricAccountBalances WHERE account = ? AND asset_id = ? AND height <= ? ORDER BY height DESC LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address, assetId, height)) {
			if (resultSet == null)
				return null;

			BigDecimal balance = resultSet.getBigDecimal(1).setScale(8);

			return new AccountBalanceData(address, assetId, balance);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account balance from repository", e);
		}
	}

	@Override
	public List<AccountBalanceData> getHistoricBalances(String address, long assetId) throws DataException {
		String sql = "SELECT height, balance FROM HistoricAccountBalances WHERE account = ? AND asset_id = ? ORDER BY height DESC";

		List<AccountBalanceData> historicBalances = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql, address, assetId)) {
			if (resultSet == null)
				return historicBalances;

			do {
				int height = resultSet.getInt(1);
				BigDecimal balance = resultSet.getBigDecimal(2);

				historicBalances.add(new AccountBalanceData(address, assetId, balance, height));
			} while (resultSet.next());

			return historicBalances;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch historic account balances from repository", e);
		}
	}

	@Override
	public List<AccountBalanceData> getAssetBalances(long assetId, Boolean excludeZero) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT account, IFNULL(balance, 0) FROM AccountBalances WHERE asset_id = ?");

		if (excludeZero != null && excludeZero)
			sql.append(" AND balance != 0");

		List<AccountBalanceData> accountBalances = new ArrayList<>();
		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), assetId)) {
			if (resultSet == null)
				return accountBalances;

			do {
				String address = resultSet.getString(1);
				BigDecimal balance = resultSet.getBigDecimal(2).setScale(8);

				accountBalances.add(new AccountBalanceData(address, assetId, balance));
			} while (resultSet.next());

			return accountBalances;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch asset balances from repository", e);
		}
	}

	@Override
	public List<AccountBalanceData> getAssetBalances(List<String> addresses, List<Long> assetIds, BalanceOrdering balanceOrdering, Boolean excludeZero,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT account, asset_id, IFNULL(balance, 0), asset_name FROM ");

		final boolean haveAddresses = addresses != null && !addresses.isEmpty();
		final boolean haveAssetIds = assetIds != null && !assetIds.isEmpty();

		// Fill temporary table with filtering addresses/assetIDs
		if (haveAddresses)
			HSQLDBRepository.temporaryValuesTableSql(sql, addresses.size(), "TmpAccounts", "account");

		if (haveAssetIds) {
			if (haveAddresses)
				sql.append("CROSS JOIN ");

			HSQLDBRepository.temporaryValuesTableSql(sql, assetIds, "TmpAssetIds", "asset_id");
		}

		if (haveAddresses || haveAssetIds) {
			// Now use temporary table to filter AccountBalances (using index) and optional zero balance exclusion
			sql.append("JOIN AccountBalances ON ");

			if (haveAddresses)
				sql.append("AccountBalances.account = TmpAccounts.account ");

			if (haveAssetIds) {
				if (haveAddresses)
					sql.append("AND ");

				sql.append("AccountBalances.asset_id = TmpAssetIds.asset_id ");
			}

			if (!haveAddresses || (excludeZero != null && excludeZero))
				sql.append("AND AccountBalances.balance != 0 ");
		} else {
			// Simpler form if no filtering
			sql.append("AccountBalances ");

			// Zero balance exclusion comes later
		}

		// Join for asset name
		sql.append("JOIN Assets ON Assets.asset_id = AccountBalances.asset_id ");

		// Zero balance exclusion if no filtering
		if (!haveAddresses && !haveAssetIds && excludeZero != null && excludeZero)
			sql.append("WHERE AccountBalances.balance != 0 ");

		if (balanceOrdering != null) {
			String[] orderingColumns;
			switch (balanceOrdering) {
				case ACCOUNT_ASSET:
					orderingColumns = new String[] { "account", "asset_id" };
					break;

				case ASSET_ACCOUNT:
					orderingColumns = new String[] { "asset_id", "account" };
					break;

				case ASSET_BALANCE_ACCOUNT:
					orderingColumns = new String[] { "asset_id", "balance", "account" };
					break;

				default:
					throw new DataException(String.format("Unsupported asset balance result ordering: %s", balanceOrdering.name()));
			}

			sql.append("ORDER BY ");
			for (int oi = 0; oi < orderingColumns.length; ++oi) {
				if (oi != 0)
					sql.append(", ");

				sql.append(orderingColumns[oi]);
				if (reverse != null && reverse)
					sql.append(" DESC");
			}
		}

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		String[] addressesArray = addresses == null ? new String[0] : addresses.toArray(new String[addresses.size()]);
		List<AccountBalanceData> accountBalances = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), (Object[]) addressesArray)) {
			if (resultSet == null)
				return accountBalances;

			do {
				String address = resultSet.getString(1);
				long assetId = resultSet.getLong(2);
				BigDecimal balance = resultSet.getBigDecimal(3).setScale(8);
				String assetName = resultSet.getString(4);

				accountBalances.add(new AccountBalanceData(address, assetId, balance, assetName));
			} while (resultSet.next());

			return accountBalances;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch asset balances from repository", e);
		}
	}

	@Override
	public void save(AccountBalanceData accountBalanceData) throws DataException {
		// If balance is zero and there are no prior historic balance, then simply delete balances for this assetId (typically during orphaning)
		if (accountBalanceData.getBalance().signum() == 0) {
			String existsSql = "account = ? AND asset_id = ? AND height < (SELECT height - 1 FROM NextBlockHeight)"; // height prior to current block. no matches (obviously) prior to genesis block

			boolean hasPriorBalances;
			try {
				hasPriorBalances = this.repository.exists("HistoricAccountBalances", existsSql, accountBalanceData.getAddress(), accountBalanceData.getAssetId());
			} catch (SQLException e) {
				throw new DataException("Unable to check for historic account balances in repository", e);
			}

			if (!hasPriorBalances) {
				try {
					this.repository.delete("AccountBalances", "account = ? AND asset_id = ?", accountBalanceData.getAddress(), accountBalanceData.getAssetId());
				} catch (SQLException e) {
					throw new DataException("Unable to delete account balance from repository", e);
				}

				// I don't think we need to do this as Block.orphan() would do this for us?
				try {
					this.repository.delete("HistoricAccountBalances", "account = ? AND asset_id = ?", accountBalanceData.getAddress(), accountBalanceData.getAssetId());
				} catch (SQLException e) {
					throw new DataException("Unable to delete historic account balances from repository", e);
				}

				return;
			}
		}

		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountBalances");

		saveHelper.bind("account", accountBalanceData.getAddress()).bind("asset_id", accountBalanceData.getAssetId()).bind("balance",
				accountBalanceData.getBalance());

		try {
			// HistoricAccountBalances auto-updated via trigger

			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account balance into repository", e);
		}
	}

	@Override
	public void delete(String address, long assetId) throws DataException {
		try {
			this.repository.delete("AccountBalances", "account = ? AND asset_id = ?", address, assetId);
		} catch (SQLException e) {
			throw new DataException("Unable to delete account balance from repository", e);
		}

		try {
			this.repository.delete("HistoricAccountBalances", "account = ? AND asset_id = ?", address, assetId);
		} catch (SQLException e) {
			throw new DataException("Unable to delete historic account balances from repository", e);
		}
	}

	@Override
	public int deleteBalancesFromHeight(int height) throws DataException {
		try {
			return this.repository.delete("HistoricAccountBalances", "height >= ?", height);
		} catch (SQLException e) {
			throw new DataException("Unable to delete historic account balances from repository", e);
		}
	}

	// Reward-Share

	@Override
	public RewardShareData getRewardShare(byte[] minterPublicKey, String recipient) throws DataException {
		String sql = "SELECT reward_share_public_key, share_percent FROM RewardShares WHERE minter_public_key = ? AND recipient = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, minterPublicKey, recipient)) {
			if (resultSet == null)
				return null;

			byte[] rewardSharePublicKey = resultSet.getBytes(1);
			BigDecimal sharePercent = resultSet.getBigDecimal(2);

			return new RewardShareData(minterPublicKey, recipient, rewardSharePublicKey, sharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share info from repository", e);
		}
	}

	@Override
	public RewardShareData getRewardShare(byte[] rewardSharePublicKey) throws DataException {
		String sql = "SELECT minter_public_key, recipient, share_percent FROM RewardShares WHERE reward_share_public_key = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, rewardSharePublicKey)) {
			if (resultSet == null)
				return null;

			byte[] minterPublicKey = resultSet.getBytes(1);
			String recipient = resultSet.getString(2);
			BigDecimal sharePercent = resultSet.getBigDecimal(3);

			return new RewardShareData(minterPublicKey, recipient, rewardSharePublicKey, sharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share info from repository", e);
		}
	}

	@Override
	public boolean isRewardSharePublicKey(byte[] publicKey) throws DataException {
		try {
			return this.repository.exists("RewardShares", "reward_share_public_key = ?", publicKey);
		} catch (SQLException e) {
			throw new DataException("Unable to check for reward-share public key in repository", e);
		}
	}

	@Override
	public int countRewardShares(byte[] minterPublicKey) throws DataException {
		String sql = "SELECT COUNT(*) FROM RewardShares WHERE minter_public_key = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, minterPublicKey)) {
			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count reward-shares in repository", e);
		}
	}

	@Override
	public List<RewardShareData> getRewardShares() throws DataException {
		String sql = "SELECT minter_public_key, recipient, share_percent, reward_share_public_key FROM RewardShares";

		List<RewardShareData> rewardShares = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql)) {
			if (resultSet == null)
				return rewardShares;

			do {
				byte[] minterPublicKey = resultSet.getBytes(1);
				String recipient = resultSet.getString(2);
				BigDecimal sharePercent = resultSet.getBigDecimal(3);
				byte[] rewardSharePublicKey = resultSet.getBytes(4);

				rewardShares.add(new RewardShareData(minterPublicKey, recipient, rewardSharePublicKey, sharePercent));
			} while (resultSet.next());

			return rewardShares;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-shares from repository", e);
		}
	}

	@Override
	public List<RewardShareData> findRewardShares(List<String> minters, List<String> recipients, List<String> involvedAddresses,
			Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT DISTINCT minter_public_key, recipient, share_percent, reward_share_public_key FROM RewardShares ");

		List<Object> args = new ArrayList<>();

		final boolean hasRecipients = recipients != null && !recipients.isEmpty();
		final boolean hasMinters = minters != null && !minters.isEmpty();
		final boolean hasInvolved = involvedAddresses != null && !involvedAddresses.isEmpty();

		if (hasMinters || hasInvolved)
			sql.append("JOIN Accounts ON Accounts.public_key = RewardShares.minter_public_key ");

		if (hasRecipients) {
			sql.append("JOIN (VALUES ");

			final int recipientsSize = recipients.size();
			for (int ri = 0; ri < recipientsSize; ++ri) {
				if (ri != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS Recipients (address) ON RewardShares.recipient = Recipients.address ");
			args.addAll(recipients);
		}

		if (hasMinters) {
			sql.append("JOIN (VALUES ");

			final int mintersSize = minters.size();
			for (int fi = 0; fi < mintersSize; ++fi) {
				if (fi != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS Minters (address) ON Accounts.account = Minters.address ");
			args.addAll(minters);
		}

		if (hasInvolved) {
			sql.append("JOIN (VALUES ");

			final int involvedAddressesSize = involvedAddresses.size();
			for (int iai = 0; iai < involvedAddressesSize; ++iai) {
				if (iai != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS Involved (address) ON Involved.address IN (RewardShares.recipient, Accounts.account) ");
			args.addAll(involvedAddresses);
		}

		sql.append("ORDER BY recipient, share_percent");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<RewardShareData> rewardShares = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), args.toArray())) {
			if (resultSet == null)
				return rewardShares;

			do {
				byte[] minterPublicKey = resultSet.getBytes(1);
				String recipient = resultSet.getString(2);
				BigDecimal sharePercent = resultSet.getBigDecimal(3);
				byte[] rewardSharePublicKey = resultSet.getBytes(4);

				rewardShares.add(new RewardShareData(minterPublicKey, recipient, rewardSharePublicKey, sharePercent));
			} while (resultSet.next());

			return rewardShares;
		} catch (SQLException e) {
			throw new DataException("Unable to find reward-shares in repository", e);
		}
	}

	@Override
	public Integer getRewardShareIndex(byte[] publicKey) throws DataException {
		String sql = "SELECT COUNT(*) FROM RewardShares WHERE reward_share_public_key < ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, publicKey)) {
			if (resultSet == null)
				return null;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to determine reward-share index in repository", e);
		}
	}

	@Override
	public RewardShareData getRewardShareByIndex(int index) throws DataException {
		String sql = "SELECT minter_public_key, recipient, share_percent, reward_share_public_key FROM RewardShares "
				+ "ORDER BY reward_share_public_key ASC "
				+ "OFFSET ? LIMIT 1";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, index)) {
			if (resultSet == null)
				return null;

			byte[] minterPublicKey = resultSet.getBytes(1);
			String recipient = resultSet.getString(2);
			BigDecimal sharePercent = resultSet.getBigDecimal(3);
			byte[] rewardSharePublicKey = resultSet.getBytes(4);

			return new RewardShareData(minterPublicKey, recipient, rewardSharePublicKey, sharePercent);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch reward-share info from repository", e);
		}
	}

	@Override
	public void save(RewardShareData rewardShareData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("RewardShares");

		saveHelper.bind("minter_public_key", rewardShareData.getMinterPublicKey()).bind("recipient", rewardShareData.getRecipient())
				.bind("reward_share_public_key", rewardShareData.getRewardSharePublicKey()).bind("share_percent", rewardShareData.getSharePercent());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save reward-share info into repository", e);
		}
	}

	@Override
	public void delete(byte[] minterPublickey, String recipient) throws DataException {
		try {
			this.repository.delete("RewardShares", "minter_public_key = ? and recipient = ?", minterPublickey, recipient);
		} catch (SQLException e) {
			throw new DataException("Unable to delete reward-share info from repository", e);
		}
	}

	// Minting accounts used by BlockMinter

	public List<MintingAccountData> getMintingAccounts() throws DataException {
		List<MintingAccountData> mintingAccounts = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute("SELECT minter_private_key FROM MintingAccounts")) {
			if (resultSet == null)
				return mintingAccounts;

			do {
				byte[] minterPrivateKey = resultSet.getBytes(1);

				mintingAccounts.add(new MintingAccountData(minterPrivateKey));
			} while (resultSet.next());

			return mintingAccounts;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch minting accounts from repository", e);
		}
	}

	public void save(MintingAccountData mintingAccountData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("MintingAccounts");

		saveHelper.bind("minter_private_key", mintingAccountData.getPrivateKey());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save minting account into repository", e);
		}
	}

	public int delete(byte[] minterPrivateKey) throws DataException {
		try {
			return this.repository.delete("MintingAccounts", "minter_private_key = ?", minterPrivateKey);
		} catch (SQLException e) {
			throw new DataException("Unable to delete minting account from repository", e);
		}
	}

	// Managing QORT from legacy QORA

	public QortFromQoraData getQortFromQoraInfo(String address) throws DataException {
		String sql = "SELECT final_qort_from_qora, final_block_height FROM AccountQortFromQoraInfo WHERE account = ?";

		try (ResultSet resultSet = this.repository.checkedExecute(sql, address)) {
			if (resultSet == null)
				return null;

			BigDecimal finalQortFromQora = resultSet.getBigDecimal(1);
			Integer finalBlockHeight = resultSet.getInt(2);
			if (finalBlockHeight == 0 && resultSet.wasNull())
				finalBlockHeight = null;

			return new QortFromQoraData(address, finalQortFromQora, finalBlockHeight);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch account qort-from-qora info from repository", e);
		}
	}

	public void save(QortFromQoraData qortFromQoraData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("AccountQortFromQoraInfo");

		saveHelper.bind("account", qortFromQoraData.getAddress())
		.bind("final_qort_from_qora", qortFromQoraData.getFinalQortFromQora())
		.bind("final_block_height", qortFromQoraData.getFinalBlockHeight());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save account qort-from-qora info into repository", e);
		}
	}

	public int deleteQortFromQoraInfo(String address) throws DataException {
		try {
			return this.repository.delete("AccountQortFromQoraInfo", "account = ?", address);
		} catch (SQLException e) {
			throw new DataException("Unable to delete qort-from-qora info from repository", e);
		}
	}

}
