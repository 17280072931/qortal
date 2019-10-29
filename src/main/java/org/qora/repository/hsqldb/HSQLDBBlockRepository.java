package org.qora.repository.hsqldb;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.qora.api.model.BlockMinterSummary;
import org.qora.data.block.BlockData;
import org.qora.data.block.BlockSummaryData;
import org.qora.data.block.BlockTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.BlockRepository;
import org.qora.repository.DataException;
import org.qora.repository.TransactionRepository;

import static org.qora.repository.hsqldb.HSQLDBRepository.toOffsetDateTime;
import static org.qora.repository.hsqldb.HSQLDBRepository.getZonedTimestampMilli;

public class HSQLDBBlockRepository implements BlockRepository {

	private static final String BLOCK_DB_COLUMNS = "version, reference, transaction_count, total_fees, "
			+ "transactions_signature, height, minted, minter, minter_signature, "
			+ "AT_count, AT_fees, online_accounts, online_accounts_count, online_accounts_timestamp, online_accounts_signatures";

	protected HSQLDBRepository repository;

	public HSQLDBBlockRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	private BlockData getBlockFromResultSet(ResultSet resultSet) throws DataException {
		if (resultSet == null)
			return null;

		try {
			int version = resultSet.getInt(1);
			byte[] reference = resultSet.getBytes(2);
			int transactionCount = resultSet.getInt(3);
			BigDecimal totalFees = resultSet.getBigDecimal(4);
			byte[] transactionsSignature = resultSet.getBytes(5);
			int height = resultSet.getInt(6);
			long timestamp = getZonedTimestampMilli(resultSet, 7);
			byte[] minterPublicKey = resultSet.getBytes(8);
			byte[] minterSignature = resultSet.getBytes(9);
			int atCount = resultSet.getInt(10);
			BigDecimal atFees = resultSet.getBigDecimal(11);
			byte[] encodedOnlineAccounts = resultSet.getBytes(12);
			int onlineAccountsCount = resultSet.getInt(13);
			Long onlineAccountsTimestamp = getZonedTimestampMilli(resultSet, 14);
			byte[] onlineAccountsSignatures = resultSet.getBytes(15);

			return new BlockData(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
					minterPublicKey, minterSignature, atCount, atFees,
					encodedOnlineAccounts, onlineAccountsCount, onlineAccountsTimestamp, onlineAccountsSignatures);
		} catch (SQLException e) {
			throw new DataException("Error extracting data from result set", e);
		}
	}

	@Override
	public BlockData fromSignature(byte[] signature) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE signature = ? LIMIT 1", signature)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by signature from repository", e);
		}
	}

	@Override
	public BlockData fromReference(byte[] reference) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE reference = ? LIMIT 1", reference)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by reference from repository", e);
		}
	}

	@Override
	public BlockData fromHeight(int height) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height = ? LIMIT 1", height)) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching block by height from repository", e);
		}
	}

	@Override
	public boolean exists(byte[] signature) throws DataException {
		try {
			return this.repository.exists("Blocks", "signature = ?", signature);
		} catch (SQLException e) {
			throw new DataException("Unable to check for block in repository", e);
		}
	}

	@Override
	public int getHeightFromSignature(byte[] signature) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT height FROM Blocks WHERE signature = ? LIMIT 1", signature)) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining block height by signature from repository", e);
		}
	}

	@Override
	public int getHeightFromTimestamp(long timestamp) throws DataException {
		// Uses (minted, height) index
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT height FROM Blocks WHERE minted <= ? ORDER BY minted DESC LIMIT 1",
				toOffsetDateTime(timestamp))) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining block height by timestamp from repository", e);
		}
	}

	@Override
	public int getBlockchainHeight() throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT height FROM Blocks ORDER BY height DESC LIMIT 1")) {
			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Error obtaining blockchain height from repository", e);
		}
	}

	@Override
	public BlockData getLastBlock() throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks ORDER BY height DESC LIMIT 1")) {
			return getBlockFromResultSet(resultSet);
		} catch (SQLException e) {
			throw new DataException("Error fetching last block from repository", e);
		}
	}

	@Override
	public List<TransactionData> getTransactionsFromSignature(byte[] signature, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(256);

		sql.append("SELECT transaction_signature FROM BlockTransactions WHERE block_signature = ? ORDER BY sequence");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<TransactionData> transactions = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), signature)) {
			if (resultSet == null)
				return transactions; // No transactions in this block

			TransactionRepository transactionRepo = this.repository.getTransactionRepository();

			// NB: do-while loop because .checkedExecute() implicitly calls ResultSet.next() for us
			do {
				byte[] transactionSignature = resultSet.getBytes(1);
				transactions.add(transactionRepo.fromSignature(transactionSignature));
			} while (resultSet.next());
		} catch (SQLException e) {
			throw new DataException("Unable to fetch block's transactions from repository", e);
		}

		return transactions;
	}

	@Override
	public int countMintedBlocks(byte[] minterPublicKey) throws DataException {
		String directSql = "SELECT COUNT(*) FROM Blocks WHERE minter = ?";

		String rewardShareSql = "SELECT COUNT(*) FROM RewardShares JOIN Blocks ON minter = reward_share_public_key WHERE minter_public_key = ?";

		int totalCount = 0;

		try (ResultSet resultSet = this.repository.checkedExecute(directSql, minterPublicKey)) {
			totalCount += resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count minted blocks in repository", e);
		}

		try (ResultSet resultSet = this.repository.checkedExecute(rewardShareSql, minterPublicKey)) {
			totalCount += resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to count reward-share minted blocks in repository", e);
		}

		return totalCount;
	}

	@Override
	public List<BlockMinterSummary> getBlockMinters(List<String> addresses, Integer limit, Integer offset, Boolean reverse) throws DataException {
		String subquerySql = "SELECT minter, COUNT(signature) FROM Blocks GROUP BY minter";

		StringBuilder sql = new StringBuilder(1024);
		sql.append("SELECT DISTINCT block_minter, n_blocks, minter_public_key, recipient FROM (");
		sql.append(subquerySql);
		sql.append(") AS Minters (block_minter, n_blocks) LEFT OUTER JOIN RewardShares ON reward_share_public_key = block_minter ");

		if (addresses != null && !addresses.isEmpty()) {
			sql.append(" LEFT OUTER JOIN Accounts AS BlockMinterAccounts ON BlockMinterAccounts.public_key = block_minter ");
			sql.append(" LEFT OUTER JOIN Accounts AS RewardShareMinterAccounts ON RewardShareMinterAccounts.public_key = minter_public_key ");
			sql.append(" JOIN (VALUES ");

			final int addressesSize = addresses.size();
			for (int ai = 0; ai < addressesSize; ++ai) {
				if (ai != 0)
					sql.append(", ");

				sql.append("(?)");
			}

			sql.append(") AS FilterAccounts (account) ");
			sql.append(" ON FilterAccounts.account IN (recipient, BlockMinterAccounts.account, RewardShareMinterAccounts.account) ");
		} else {
			addresses = Collections.emptyList();
		}

		sql.append("ORDER BY n_blocks ");
		if (reverse != null && reverse)
			sql.append("DESC ");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<BlockMinterSummary> summaries = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), addresses.toArray())) {
			if (resultSet == null)
				return summaries;

			do {
				byte[] blockMinterPublicKey = resultSet.getBytes(1);
				int nBlocks = resultSet.getInt(2);
				byte[] mintingAccountPublicKey = resultSet.getBytes(3);
				String recipientAccount = resultSet.getString(4);

				BlockMinterSummary blockMinterSummary;
				if (recipientAccount == null)
					blockMinterSummary = new BlockMinterSummary(blockMinterPublicKey, nBlocks);
				else
					blockMinterSummary = new BlockMinterSummary(blockMinterPublicKey, nBlocks, mintingAccountPublicKey, recipientAccount);

				summaries.add(blockMinterSummary);
			} while (resultSet.next());

			return summaries;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch block minters from repository", e);
		}
	}

	@Override
	public List<BlockData> getBlocksByMinter(byte[] minterPublicKey, Integer limit, Integer offset, Boolean reverse) throws DataException {
		StringBuilder sql = new StringBuilder(512);
		sql.append("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE minter = ? ORDER BY height ");
		if (reverse != null && reverse)
			sql.append(" DESC");

		HSQLDBRepository.limitOffsetSql(sql, limit, offset);

		List<BlockData> blockData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql.toString(), minterPublicKey)) {
			if (resultSet == null)
				return blockData;

			do {
				blockData.add(getBlockFromResultSet(resultSet));
			} while (resultSet.next());

			return blockData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch minter's blocks from repository", e);
		}
	}

	@Override
	public List<BlockData> getBlocks(int firstBlockHeight, int lastBlockHeight) throws DataException {
		String sql = "SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height BETWEEN ? AND ?";

		List<BlockData> blockData = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, firstBlockHeight, lastBlockHeight)) {
			if (resultSet == null)
				return blockData;

			do {
				blockData.add(getBlockFromResultSet(resultSet));
			} while (resultSet.next());

			return blockData;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch height-ranged blocks from repository", e);
		}
	}

	@Override
	public List<BlockSummaryData> getBlockSummaries(int firstBlockHeight, int lastBlockHeight) throws DataException {
		String sql = "SELECT signature, height, minter, online_accounts_count FROM Blocks WHERE height BETWEEN ? AND ?";

		List<BlockSummaryData> blockSummaries = new ArrayList<>();

		try (ResultSet resultSet = this.repository.checkedExecute(sql, firstBlockHeight, lastBlockHeight)) {
			if (resultSet == null)
				return blockSummaries;

			do {
				byte[] signature = resultSet.getBytes(1);
				int height = resultSet.getInt(2);
				byte[] minterPublicKey = resultSet.getBytes(3);
				int onlineAccountsCount = resultSet.getInt(4);

				BlockSummaryData blockSummary = new BlockSummaryData(height, signature, minterPublicKey, onlineAccountsCount);
				blockSummaries.add(blockSummary);
			} while (resultSet.next());

			return blockSummaries;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch height-ranged block summaries from repository", e);
		}
	}

	@Override
	public int trimOldOnlineAccountsSignatures(long timestamp) throws DataException {
		String sql = "UPDATE Blocks set online_accounts_signatures = NULL WHERE minted < ? AND online_accounts_signatures IS NOT NULL";

		try {
			return this.repository.checkedExecuteUpdateCount(sql, toOffsetDateTime(timestamp));
		} catch (SQLException e) {
			throw new DataException("Unable to trim old online accounts signatures in repository", e);
		}
	}

	@Override
	public void save(BlockData blockData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("Blocks");

		saveHelper.bind("signature", blockData.getSignature()).bind("version", blockData.getVersion()).bind("reference", blockData.getReference())
				.bind("transaction_count", blockData.getTransactionCount()).bind("total_fees", blockData.getTotalFees())
				.bind("transactions_signature", blockData.getTransactionsSignature()).bind("height", blockData.getHeight())
				.bind("minted", toOffsetDateTime(blockData.getTimestamp()))
				.bind("minter", blockData.getMinterPublicKey()).bind("minter_signature", blockData.getMinterSignature())
				.bind("AT_count", blockData.getATCount()).bind("AT_fees", blockData.getATFees())
				.bind("online_accounts", blockData.getEncodedOnlineAccounts()).bind("online_accounts_count", blockData.getOnlineAccountsCount())
				.bind("online_accounts_timestamp", toOffsetDateTime(blockData.getOnlineAccountsTimestamp()))
				.bind("online_accounts_signatures", blockData.getOnlineAccountsSignatures());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save Block into repository", e);
		}
	}

	@Override
	public void delete(BlockData blockData) throws DataException {
		try {
			this.repository.delete("Blocks", "signature = ?", blockData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete Block from repository", e);
		}
	}

	@Override
	public void save(BlockTransactionData blockTransactionData) throws DataException {
		HSQLDBSaver saveHelper = new HSQLDBSaver("BlockTransactions");

		saveHelper.bind("block_signature", blockTransactionData.getBlockSignature()).bind("sequence", blockTransactionData.getSequence())
				.bind("transaction_signature", blockTransactionData.getTransactionSignature());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save BlockTransaction into repository", e);
		}
	}

	@Override
	public void delete(BlockTransactionData blockTransactionData) throws DataException {
		try {
			this.repository.delete("BlockTransactions", "block_signature = ? AND sequence = ? AND transaction_signature = ?",
					blockTransactionData.getBlockSignature(), blockTransactionData.getSequence(), blockTransactionData.getTransactionSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete BlockTransaction from repository", e);
		}
	}

}
