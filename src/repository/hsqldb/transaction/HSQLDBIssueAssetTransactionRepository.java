package repository.hsqldb.transaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import data.transaction.IssueAssetTransactionData;
import data.transaction.TransactionData;
import repository.DataException;
import repository.hsqldb.HSQLDBRepository;
import repository.hsqldb.HSQLDBSaver;

public class HSQLDBIssueAssetTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBIssueAssetTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(byte[] signature, byte[] reference, byte[] creatorPublicKey, long timestamp, BigDecimal fee) throws DataException {
		try {
			ResultSet rs = this.repository.checkedExecute(
					"SELECT issuer, owner, asset_name, description, quantity, is_divisible, asset_id FROM IssueAssetTransactions WHERE signature = ?",
					signature);
			if (rs == null)
				return null;

			byte[] issuerPublicKey = this.repository.getResultSetBytes(rs.getBinaryStream(1));
			String owner = rs.getString(2);
			String assetName = rs.getString(3);
			String description = rs.getString(4);
			long quantity = rs.getLong(5);
			boolean isDivisible = rs.getBoolean(6);
			Long assetId = rs.getLong(7);

			return new IssueAssetTransactionData(assetId, issuerPublicKey, owner, assetName, description, quantity, isDivisible, fee, timestamp, reference,
					signature);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch issue asset transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		super.save(transactionData);

		IssueAssetTransactionData issueAssetTransactionData = (IssueAssetTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("IssueAssetTransactions");

		saveHelper.bind("signature", issueAssetTransactionData.getSignature()).bind("issuer", issueAssetTransactionData.getIssuerPublicKey())
				.bind("asset_name", issueAssetTransactionData.getAssetName()).bind("description", issueAssetTransactionData.getDescription())
				.bind("quantity", issueAssetTransactionData.getQuantity()).bind("is_divisible", issueAssetTransactionData.getIsDivisible())
				.bind("asset_id", issueAssetTransactionData.getAssetId());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save issue asset transaction into repository", e);
		}
	}

}
