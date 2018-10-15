package repository.hsqldb.transaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import data.PaymentData;
import data.block.BlockData;
import data.transaction.TransactionData;
import qora.transaction.Transaction.TransactionType;
import repository.DataException;
import repository.TransactionRepository;
import repository.hsqldb.HSQLDBRepository;
import repository.hsqldb.HSQLDBSaver;

public class HSQLDBTransactionRepository implements TransactionRepository {

	protected HSQLDBRepository repository;
	private HSQLDBGenesisTransactionRepository genesisTransactionRepository;
	private HSQLDBPaymentTransactionRepository paymentTransactionRepository;
	private HSQLDBRegisterNameTransactionRepository registerNameTransactionRepository;
	private HSQLDBUpdateNameTransactionRepository updateNameTransactionRepository;
	private HSQLDBSellNameTransactionRepository sellNameTransactionRepository;
	private HSQLDBCancelSellNameTransactionRepository cancelSellNameTransactionRepository;
	private HSQLDBBuyNameTransactionRepository buyNameTransactionRepository;
	private HSQLDBCreatePollTransactionRepository createPollTransactionRepository;
	private HSQLDBVoteOnPollTransactionRepository voteOnPollTransactionRepository;
	private HSQLDBArbitraryTransactionRepository arbitraryTransactionRepository;
	private HSQLDBIssueAssetTransactionRepository issueAssetTransactionRepository;
	private HSQLDBTransferAssetTransactionRepository transferAssetTransactionRepository;
	private HSQLDBCreateOrderTransactionRepository createOrderTransactionRepository;
	private HSQLDBCancelOrderTransactionRepository cancelOrderTransactionRepository;
	private HSQLDBMultiPaymentTransactionRepository multiPaymentTransactionRepository;
	private HSQLDBDeployATTransactionRepository deployATTransactionRepository;
	private HSQLDBMessageTransactionRepository messageTransactionRepository;
	private HSQLDBATTransactionRepository atTransactionRepository;

	public HSQLDBTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
		this.genesisTransactionRepository = new HSQLDBGenesisTransactionRepository(repository);
		this.paymentTransactionRepository = new HSQLDBPaymentTransactionRepository(repository);
		this.registerNameTransactionRepository = new HSQLDBRegisterNameTransactionRepository(repository);
		this.updateNameTransactionRepository = new HSQLDBUpdateNameTransactionRepository(repository);
		this.sellNameTransactionRepository = new HSQLDBSellNameTransactionRepository(repository);
		this.cancelSellNameTransactionRepository = new HSQLDBCancelSellNameTransactionRepository(repository);
		this.buyNameTransactionRepository = new HSQLDBBuyNameTransactionRepository(repository);
		this.createPollTransactionRepository = new HSQLDBCreatePollTransactionRepository(repository);
		this.voteOnPollTransactionRepository = new HSQLDBVoteOnPollTransactionRepository(repository);
		this.arbitraryTransactionRepository = new HSQLDBArbitraryTransactionRepository(repository);
		this.issueAssetTransactionRepository = new HSQLDBIssueAssetTransactionRepository(repository);
		this.transferAssetTransactionRepository = new HSQLDBTransferAssetTransactionRepository(repository);
		this.createOrderTransactionRepository = new HSQLDBCreateOrderTransactionRepository(repository);
		this.cancelOrderTransactionRepository = new HSQLDBCancelOrderTransactionRepository(repository);
		this.multiPaymentTransactionRepository = new HSQLDBMultiPaymentTransactionRepository(repository);
		this.deployATTransactionRepository = new HSQLDBDeployATTransactionRepository(repository);
		this.messageTransactionRepository = new HSQLDBMessageTransactionRepository(repository);
		this.atTransactionRepository = new HSQLDBATTransactionRepository(repository);
	}

	protected HSQLDBTransactionRepository() {
	}

	@Override
	public TransactionData fromSignature(byte[] signature) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT type, reference, creator, creation, fee FROM Transactions WHERE signature = ?",
				signature)) {
			if (resultSet == null)
				return null;

			TransactionType type = TransactionType.valueOf(resultSet.getInt(1));
			byte[] reference = resultSet.getBytes(2);
			byte[] creatorPublicKey = resultSet.getBytes(3);
			long timestamp = resultSet.getTimestamp(4, Calendar.getInstance(HSQLDBRepository.UTC)).getTime();
			BigDecimal fee = resultSet.getBigDecimal(5).setScale(8);

			return this.fromBase(type, signature, reference, creatorPublicKey, timestamp, fee);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction from repository", e);
		}
	}

	@Override
	public TransactionData fromReference(byte[] reference) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT type, signature, creator, creation, fee FROM Transactions WHERE reference = ?",
				reference)) {
			if (resultSet == null)
				return null;

			TransactionType type = TransactionType.valueOf(resultSet.getInt(1));
			byte[] signature = resultSet.getBytes(2);
			byte[] creatorPublicKey = resultSet.getBytes(3);
			long timestamp = resultSet.getTimestamp(4, Calendar.getInstance(HSQLDBRepository.UTC)).getTime();
			BigDecimal fee = resultSet.getBigDecimal(5).setScale(8);

			return this.fromBase(type, signature, reference, creatorPublicKey, timestamp, fee);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction from repository", e);
		}
	}

	private TransactionData fromBase(TransactionType type, byte[] signature, byte[] reference, byte[] creatorPublicKey, long timestamp, BigDecimal fee)
			throws DataException {
		switch (type) {
			case GENESIS:
				return this.genesisTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case PAYMENT:
				return this.paymentTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case REGISTER_NAME:
				return this.registerNameTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case UPDATE_NAME:
				return this.updateNameTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case SELL_NAME:
				return this.sellNameTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case CANCEL_SELL_NAME:
				return this.cancelSellNameTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case BUY_NAME:
				return this.buyNameTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case CREATE_POLL:
				return this.createPollTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case VOTE_ON_POLL:
				return this.voteOnPollTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case ARBITRARY:
				return this.arbitraryTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case ISSUE_ASSET:
				return this.issueAssetTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case TRANSFER_ASSET:
				return this.transferAssetTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case CREATE_ASSET_ORDER:
				return this.createOrderTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case CANCEL_ASSET_ORDER:
				return this.cancelOrderTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case MULTIPAYMENT:
				return this.multiPaymentTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case DEPLOY_AT:
				return this.deployATTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case MESSAGE:
				return this.messageTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			case AT:
				return this.atTransactionRepository.fromBase(signature, reference, creatorPublicKey, timestamp, fee);

			default:
				throw new DataException("Unsupported transaction type [" + type.name() + "] during fetch from HSQLDB repository");
		}
	}

	protected List<PaymentData> getPaymentsFromSignature(byte[] signature) throws DataException {
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT recipient, amount, asset_id FROM SharedTransactionPayments WHERE signature = ?",
				signature)) {
			if (resultSet == null)
				return null;

			List<PaymentData> payments = new ArrayList<PaymentData>();

			// NOTE: do-while because checkedExecute() above has already called rs.next() for us
			do {
				String recipient = resultSet.getString(1);
				BigDecimal amount = resultSet.getBigDecimal(2);
				long assetId = resultSet.getLong(3);

				payments.add(new PaymentData(recipient, assetId, amount));
			} while (resultSet.next());

			return payments;
		} catch (SQLException e) {
			throw new DataException("Unable to fetch payments from repository", e);
		}
	}

	protected void savePayments(byte[] signature, List<PaymentData> payments) throws DataException {
		for (PaymentData paymentData : payments) {
			HSQLDBSaver saver = new HSQLDBSaver("SharedTransactionPayments");

			saver.bind("signature", signature).bind("recipient", paymentData.getRecipient()).bind("amount", paymentData.getAmount()).bind("asset_id",
					paymentData.getAssetId());

			try {
				saver.execute(this.repository);
			} catch (SQLException e) {
				throw new DataException("Unable to save payment into repository", e);
			}
		}
	}

	@Override
	public int getHeightFromSignature(byte[] signature) throws DataException {
		if (signature == null)
			return 0;

		// Fetch height using join via block's transactions
		try (ResultSet resultSet = this.repository.checkedExecute(
				"SELECT height from BlockTransactions JOIN Blocks ON Blocks.signature = BlockTransactions.block_signature WHERE transaction_signature = ? LIMIT 1",
				signature)) {

			if (resultSet == null)
				return 0;

			return resultSet.getInt(1);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch transaction's height from repository", e);
		}
	}

	@Override
	public BlockData getBlockDataFromSignature(byte[] signature) throws DataException {
		if (signature == null)
			return null;

		// Fetch block signature (if any)
		try (ResultSet resultSet = this.repository.checkedExecute("SELECT block_signature from BlockTransactions WHERE transaction_signature = ? LIMIT 1",
				signature)) {
			if (resultSet == null)
				return null;

			byte[] blockSignature = resultSet.getBytes(1);

			return this.repository.getBlockRepository().fromSignature(blockSignature);
		} catch (SQLException | DataException e) {
			throw new DataException("Unable to fetch transaction's block from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		HSQLDBSaver saver = new HSQLDBSaver("Transactions");
		saver.bind("signature", transactionData.getSignature()).bind("reference", transactionData.getReference()).bind("type", transactionData.getType().value)
				.bind("creator", transactionData.getCreatorPublicKey()).bind("creation", new Timestamp(transactionData.getTimestamp()))
				.bind("fee", transactionData.getFee()).bind("milestone_block", null);
		try {
			saver.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save transaction into repository", e);
		}

		// Now call transaction-type-specific save() method
		switch (transactionData.getType()) {
			case GENESIS:
				this.genesisTransactionRepository.save(transactionData);
				break;

			case PAYMENT:
				this.paymentTransactionRepository.save(transactionData);
				break;

			case REGISTER_NAME:
				this.registerNameTransactionRepository.save(transactionData);
				break;

			case UPDATE_NAME:
				this.updateNameTransactionRepository.save(transactionData);
				break;

			case SELL_NAME:
				this.sellNameTransactionRepository.save(transactionData);
				break;

			case CANCEL_SELL_NAME:
				this.cancelSellNameTransactionRepository.save(transactionData);
				break;

			case BUY_NAME:
				this.buyNameTransactionRepository.save(transactionData);
				break;

			case CREATE_POLL:
				this.createPollTransactionRepository.save(transactionData);
				break;

			case VOTE_ON_POLL:
				this.voteOnPollTransactionRepository.save(transactionData);
				break;

			case ARBITRARY:
				this.arbitraryTransactionRepository.save(transactionData);
				break;

			case ISSUE_ASSET:
				this.issueAssetTransactionRepository.save(transactionData);
				break;

			case TRANSFER_ASSET:
				this.transferAssetTransactionRepository.save(transactionData);
				break;

			case CREATE_ASSET_ORDER:
				this.createOrderTransactionRepository.save(transactionData);
				break;

			case CANCEL_ASSET_ORDER:
				this.cancelOrderTransactionRepository.save(transactionData);
				break;

			case MULTIPAYMENT:
				this.multiPaymentTransactionRepository.save(transactionData);
				break;

			case DEPLOY_AT:
				this.deployATTransactionRepository.save(transactionData);
				break;

			case MESSAGE:
				this.messageTransactionRepository.save(transactionData);
				break;

			case AT:
				this.atTransactionRepository.save(transactionData);
				break;

			default:
				throw new DataException("Unsupported transaction type [" + transactionData.getType().name() + "] during save into HSQLDB repository");
		}
	}

	@Override
	public void delete(TransactionData transactionData) throws DataException {
		// NOTE: The corresponding row in sub-table is deleted automatically by the database thanks to "ON DELETE CASCADE" in the sub-table's FOREIGN KEY
		// definition.
		try {
			this.repository.delete("Transactions", "signature = ?", transactionData.getSignature());
		} catch (SQLException e) {
			throw new DataException("Unable to delete transaction from repository", e);
		}
	}

}
