package qora.block;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.json.simple.JSONObject;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Longs;

import database.DB;
import database.NoDataFoundException;
import qora.account.PrivateKeyAccount;
import qora.account.PublicKeyAccount;
import qora.assets.Asset;
import qora.transaction.Transaction;
import qora.transaction.TransactionFactory;

/*
 * Typical use-case scenarios:
 * 
 * 1. Loading a Block from the database using height, signature, reference, etc.
 * 2. Generating a new block, adding unconfirmed transactions
 * 3. Receiving a block from another node
 * 
 * Transaction count, transactions signature and total fees need to be maintained by Block.
 * In scenario (1) these can be found in database.
 * In scenarios (2) and (3) Transactions are added to the Block via addTransaction() method.
 * Also in scenarios (2) and (3), Block is responsible for saving Transactions to DB.
 * 
 * When is height set?
 * In scenario (1) this can be found in database.
 * In scenarios (2) and (3) this will need to be set after successful processing,
 * but before Block is saved into database.
 * 
 * GeneratorSignature's data is: reference + generatingBalance + generator's public key
 * TransactionSignature's data is: generatorSignature + transaction signatures
 * Block signature is: generatorSignature + transactionsSignature
 */

public class Block {

	// Validation results
	public static final int VALIDATE_OK = 1;

	// Columns when fetching from database
	private static final String DB_COLUMNS = "version, reference, transaction_count, total_fees, "
			+ "transactions_signature, height, generation, generating_balance, generator, generator_signature, " + "AT_data, AT_fees";

	// Database properties
	protected int version;
	protected byte[] reference;
	protected int transactionCount;
	protected BigDecimal totalFees;
	protected byte[] transactionsSignature;
	protected int height;
	protected long timestamp;
	protected BigDecimal generatingBalance;
	protected PublicKeyAccount generator;
	protected byte[] generatorSignature;
	protected byte[] atBytes;
	protected BigDecimal atFees;

	// Other properties
	protected List<Transaction> transactions;

	// Property lengths for serialisation
	protected static final int VERSION_LENGTH = 4;
	protected static final int TRANSACTIONS_SIGNATURE_LENGTH = 64;
	protected static final int GENERATOR_SIGNATURE_LENGTH = 64;
	protected static final int REFERENCE_LENGTH = GENERATOR_SIGNATURE_LENGTH + TRANSACTIONS_SIGNATURE_LENGTH;
	protected static final int TIMESTAMP_LENGTH = 8;
	protected static final int GENERATING_BALANCE_LENGTH = 8;
	protected static final int GENERATOR_LENGTH = 32;
	protected static final int TRANSACTION_COUNT_LENGTH = 8;
	protected static final int BASE_LENGTH = VERSION_LENGTH + REFERENCE_LENGTH + TIMESTAMP_LENGTH + GENERATING_BALANCE_LENGTH + GENERATOR_LENGTH
			+ TRANSACTIONS_SIGNATURE_LENGTH + GENERATOR_SIGNATURE_LENGTH + TRANSACTION_COUNT_LENGTH;

	// Other length constants
	protected static final int BLOCK_SIGNATURE_LENGTH = GENERATOR_SIGNATURE_LENGTH + TRANSACTIONS_SIGNATURE_LENGTH;
	public static final int MAX_BLOCK_BYTES = 1048576;
	protected static final int TRANSACTION_SIZE_LENGTH = 4; // per transaction
	public static final int MAX_TRANSACTION_BYTES = MAX_BLOCK_BYTES - BASE_LENGTH;
	protected static final int AT_BYTES_LENGTH = 4;
	protected static final int AT_FEES_LENGTH = 8;
	protected static final int AT_LENGTH = AT_FEES_LENGTH + AT_BYTES_LENGTH;

	// Constructors

	// For creating a new block from scratch or instantiating one that was previously serialized
	protected Block(int version, byte[] reference, long timestamp, BigDecimal generatingBalance, PublicKeyAccount generator, byte[] generatorSignature,
			byte[] transactionsSignature, byte[] atBytes, BigDecimal atFees) {
		this.version = version;
		this.reference = reference;
		this.timestamp = timestamp;
		this.generatingBalance = generatingBalance;
		this.generator = generator;
		this.generatorSignature = generatorSignature;
		this.height = 0;

		this.transactionCount = 0;
		this.transactions = new ArrayList<Transaction>();
		this.transactionsSignature = transactionsSignature;
		this.totalFees = BigDecimal.ZERO.setScale(8);

		this.atBytes = atBytes;
		this.atFees = atFees;
		if (this.atFees != null)
			this.totalFees = this.totalFees.add(this.atFees);
	}

	// Getters/setters

	public int getVersion() {
		return this.version;
	}

	public byte[] getReference() {
		return this.reference;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public BigDecimal getGeneratingBalance() {
		return this.generatingBalance;
	}

	public PublicKeyAccount getGenerator() {
		return this.generator;
	}

	public byte[] getGeneratorSignature() {
		return this.generatorSignature;
	}

	public byte[] getTransactionsSignature() {
		return this.transactionsSignature;
	}

	public BigDecimal getTotalFees() {
		return this.totalFees;
	}

	public int getTransactionCount() {
		return this.transactionCount;
	}

	public byte[] getATBytes() {
		return this.atBytes;
	}

	public BigDecimal getATFees() {
		return this.atFees;
	}

	public int getHeight() {
		return this.height;
	}

	// More information

	/**
	 * Return composite block signature (generatorSignature + transactionsSignature).
	 * 
	 * @return byte[], or null if either component signature is null.
	 */
	public byte[] getSignature() {
		if (this.generatorSignature == null || this.transactionsSignature == null)
			return null;

		return Bytes.concat(this.generatorSignature, this.transactionsSignature);
	}

	public int getDataLength() {
		int blockLength = BASE_LENGTH;

		if (version >= 2 && this.atBytes != null)
			blockLength += AT_FEES_LENGTH + AT_BYTES_LENGTH + this.atBytes.length;

		// Short cut for no transactions
		if (this.transactions == null || this.transactions.isEmpty())
			return blockLength;

		for (Transaction transaction : this.transactions)
			blockLength += TRANSACTION_SIZE_LENGTH + transaction.getDataLength();

		return blockLength;
	}

	/**
	 * Return block's transactions.
	 * <p>
	 * If the block was loaded from DB then it's possible this method will call the DB to load the transactions if they are not already loaded.
	 * 
	 * @return
	 * @throws SQLException
	 */
	public List<Transaction> getTransactions() throws SQLException {
		// Already loaded?
		if (this.transactions != null)
			return this.transactions;

		// Allocate cache for results
		this.transactions = new ArrayList<Transaction>();

		ResultSet rs = DB.executeUsingBytes("SELECT transaction_signature FROM BlockTransactions WHERE block_signature = ?", this.getSignature());
		if (rs == null)
			return this.transactions; // No transactions in this block

		// NB: do-while loop because DB.executeUsingBytes() implicitly calls ResultSet.next() for us
		do {
			byte[] transactionSignature = DB.getResultSetBytes(rs.getBinaryStream(1), Transaction.SIGNATURE_LENGTH);
			this.transactions.add(TransactionFactory.fromSignature(transactionSignature));

			// No need to update totalFees as this will be loaded via the Blocks table
		} while (rs.next());

		return this.transactions;
	}

	// Load/Save

	protected Block(byte[] signature) throws SQLException {
		this(DB.executeUsingBytes("SELECT " + DB_COLUMNS + " FROM Blocks WHERE signature = ?", signature));
	}

	protected Block(ResultSet rs) throws SQLException {
		if (rs == null)
			throw new NoDataFoundException();

		this.version = rs.getInt(1);
		this.reference = DB.getResultSetBytes(rs.getBinaryStream(2), REFERENCE_LENGTH);
		this.transactionCount = rs.getInt(3);
		this.totalFees = rs.getBigDecimal(4);
		this.transactionsSignature = DB.getResultSetBytes(rs.getBinaryStream(5), TRANSACTIONS_SIGNATURE_LENGTH);
		this.height = rs.getInt(6);
		this.timestamp = rs.getTimestamp(7).getTime();
		this.generatingBalance = rs.getBigDecimal(8);
		// Note: can't use GENERATOR_LENGTH in case we encounter Genesis Account's short, 8-byte public key
		this.generator = new PublicKeyAccount(DB.getResultSetBytes(rs.getBinaryStream(9)));
		this.generatorSignature = DB.getResultSetBytes(rs.getBinaryStream(10), GENERATOR_SIGNATURE_LENGTH);
		this.atBytes = DB.getResultSetBytes(rs.getBinaryStream(11));
		this.atFees = rs.getBigDecimal(12);
	}

	/**
	 * Load Block from DB using block signature.
	 * 
	 * @param signature
	 * @return Block, or null if not found
	 * @throws SQLException
	 */
	public static Block fromSignature(byte[] signature) throws SQLException {
		try {
			return new Block(signature);
		} catch (NoDataFoundException e) {
			return null;
		}
	}

	/**
	 * Load Block from DB using block height
	 * 
	 * @param height
	 * @return Block, or null if not found
	 * @throws SQLException
	 */
	public static Block fromHeight(int height) throws SQLException {
		try (final Connection connection = DB.getConnection()) {
			PreparedStatement preparedStatement = connection.prepareStatement("SELECT " + DB_COLUMNS + " FROM Blocks WHERE height = ?");
			preparedStatement.setInt(1, height);

			try {
				return new Block(DB.checkedExecute(preparedStatement));
			} catch (NoDataFoundException e) {
				return null;
			}
		}
	}

	protected void save(Connection connection) throws SQLException {
		String sql = DB.formatInsertWithPlaceholders("Blocks", "signature", "version", "reference", "transaction_count", "total_fees", "transactions_signature",
				"height", "generation", "generating_balance", "generator", "generator_signature", "AT_data", "AT_fees");
		PreparedStatement preparedStatement = connection.prepareStatement(sql);
		DB.bindInsertPlaceholders(preparedStatement, this.getSignature(), this.version, this.reference, this.transactionCount, this.totalFees,
				this.transactionsSignature, this.height, new Timestamp(this.timestamp), this.generatingBalance, this.generator.getPublicKey(),
				this.generatorSignature, this.atBytes, this.atFees);
		preparedStatement.execute();
	}

	// Navigation

	/**
	 * Load parent Block from DB
	 * 
	 * @return Block, or null if not found
	 * @throws SQLException
	 */
	public Block getParent() throws SQLException {
		try {
			return new Block(this.reference);
		} catch (NoDataFoundException e) {
			return null;
		}
	}

	/**
	 * Load child Block from DB
	 * 
	 * @return Block, or null if not found
	 * @throws SQLException
	 */
	public Block getChild() throws SQLException {
		byte[] blockSignature = this.getSignature();
		if (blockSignature == null)
			return null;

		ResultSet resultSet = DB.executeUsingBytes("SELECT " + DB_COLUMNS + " FROM Blocks WHERE reference = ?", blockSignature);

		try {
			return new Block(resultSet);
		} catch (NoDataFoundException e) {
			return null;
		}
	}

	// Converters

	public JSONObject toJSON() {
		// TODO
		return null;
	}

	public byte[] toBytes() {
		// TODO
		return null;
	}

	// Processing

	public boolean addTransaction(Transaction transaction) {
		// TODO
		// Check there is space in block
		// Add to block
		// Update transaction count
		// Update totalFees
		// Update transactions signature
		return false; // no room
	}

	public byte[] calcSignature(PrivateKeyAccount signer) {
		// TODO
		return null;
	}

	private byte[] getBytesForSignature() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(REFERENCE_LENGTH + GENERATING_BALANCE_LENGTH + GENERATOR_LENGTH);
			// Only copy the generator signature from reference, which is the first 64 bytes.
			bytes.write(Arrays.copyOf(this.reference, GENERATOR_SIGNATURE_LENGTH));
			bytes.write(Longs.toByteArray(this.generatingBalance.longValue()));
			// We're padding here just in case the generator is the genesis account whose public key is only 8 bytes long.
			bytes.write(Bytes.ensureCapacity(this.generator.getPublicKey(), GENERATOR_LENGTH, 0));
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean isSignatureValid() {
		// Check generator's signature first
		if (!this.generator.verify(this.generatorSignature, getBytesForSignature()))
			return false;

		// Check transactions signature
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(GENERATOR_SIGNATURE_LENGTH + this.transactionCount * Transaction.SIGNATURE_LENGTH);
		try {
			bytes.write(this.generatorSignature);

			for (Transaction transaction : this.getTransactions()) {
				if (!transaction.isSignatureValid())
					return false;

				bytes.write(transaction.getSignature());
			}
		} catch (IOException | SQLException e) {
			throw new RuntimeException(e);
		}

		if (!this.generator.verify(this.transactionsSignature, bytes.toByteArray()))
			return false;

		return true;
	}

	public boolean isValid(Connection connection) throws SQLException {
		// TODO
		return false;
	}

	public void process(Connection connection) throws SQLException {
		// Process transactions (we'll link them to this block after saving the block itself)
		List<Transaction> transactions = this.getTransactions();
		for (Transaction transaction : transactions)
			transaction.process(connection);

		// If fees are non-zero then add fees to generator's balance
		BigDecimal blockFee = this.getTotalFees();
		if (blockFee.compareTo(BigDecimal.ZERO) == 1)
			this.generator.setConfirmedBalance(connection, Asset.QORA, this.generator.getConfirmedBalance(Asset.QORA).add(blockFee));

		// Link block into blockchain by fetching signature of highest block and setting that as our reference
		int blockchainHeight = BlockChain.getHeight();
		Block latestBlock = Block.fromHeight(blockchainHeight);
		if (latestBlock != null)
			this.reference = latestBlock.getSignature();
		this.height = blockchainHeight + 1;
		this.save(connection);

		// Link transactions to this block, thus removing them from unconfirmed transactions list.
		for (int sequence = 0; sequence < transactions.size(); ++sequence) {
			Transaction transaction = transactions.get(sequence);

			// Link transaction to this block
			BlockTransaction blockTransaction = new BlockTransaction(this.getSignature(), sequence, transaction.getSignature());
			blockTransaction.save(connection);
		}
	}

	public void orphan(Connection connection) {
		// TODO
	}

}
