package qora.transaction;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Map;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toMap;

import org.json.simple.JSONObject;

import database.DB;
import database.NoDataFoundException;
import database.SaveHelper;
import qora.account.PrivateKeyAccount;
import qora.account.PublicKeyAccount;
import qora.block.Block;
import qora.block.BlockChain;
import qora.block.BlockTransaction;
import settings.Settings;

import utils.Base58;
import utils.ParseException;

public abstract class Transaction {

	// Transaction types
	public enum TransactionType {
		GENESIS(1), PAYMENT(2), REGISTER_NAME(3), UPDATE_NAME(4), SELL_NAME(5), CANCEL_SELL_NAME(6), BUY_NAME(7), CREATE_POLL(8), VOTE_ON_POLL(9), ARBITRARY(
				10), ISSUE_ASSET(11), TRANSFER_ASSET(12), CREATE_ASSET_ORDER(13), CANCEL_ASSET_ORDER(14), MULTIPAYMENT(15), DEPLOY_AT(16), MESSAGE(17);

		public final int value;

		private final static Map<Integer, TransactionType> map = stream(TransactionType.values()).collect(toMap(type -> type.value, type -> type));

		TransactionType(int value) {
			this.value = value;
		}

		public static TransactionType valueOf(int value) {
			return map.get(value);
		}
	}

	// Validation results
	public enum ValidationResult {
		OK(1), INVALID_ADDRESS(2), NEGATIVE_AMOUNT(3), NEGATIVE_FEE(4), NO_BALANCE(5), INVALID_REFERENCE(6), INVALID_DATA_LENGTH(27), ASSET_DOES_NOT_EXIST(
				29), NOT_YET_RELEASED(1000);

		public final int value;

		private final static Map<Integer, ValidationResult> map = stream(ValidationResult.values()).collect(toMap(result -> result.value, result -> result));

		ValidationResult(int value) {
			this.value = value;
		}

		public static ValidationResult valueOf(int value) {
			return map.get(value);
		}
	}

	// Minimum fee
	public static final BigDecimal MINIMUM_FEE = BigDecimal.ONE;

	// Cached info to make transaction processing faster
	protected static final BigDecimal maxBytePerFee = BigDecimal.valueOf(Settings.getInstance().getMaxBytePerFee());
	protected static final BigDecimal minFeePerByte = BigDecimal.ONE.divide(maxBytePerFee, MathContext.DECIMAL32);

	// Database properties shared with all transaction types
	protected TransactionType type;
	protected PublicKeyAccount creator;
	protected long timestamp;
	protected byte[] reference;
	protected BigDecimal fee;
	protected byte[] signature;

	// Derived/cached properties

	// Property lengths for serialisation
	protected static final int TYPE_LENGTH = 4;
	protected static final int TIMESTAMP_LENGTH = 8;
	public static final int REFERENCE_LENGTH = 64;
	protected static final int FEE_LENGTH = 8;
	public static final int SIGNATURE_LENGTH = 64;
	protected static final int BASE_TYPELESS_LENGTH = TIMESTAMP_LENGTH + REFERENCE_LENGTH + FEE_LENGTH + SIGNATURE_LENGTH;

	// Other length constants
	public static final int CREATOR_LENGTH = 32;
	public static final int RECIPIENT_LENGTH = 25;

	// Constructors

	protected Transaction(TransactionType type, BigDecimal fee, PublicKeyAccount creator, long timestamp, byte[] reference, byte[] signature) {
		this.fee = fee;
		this.type = type;
		this.creator = creator;
		this.timestamp = timestamp;
		this.reference = reference;
		this.signature = signature;
	}

	protected Transaction(TransactionType type, BigDecimal fee, PublicKeyAccount creator, long timestamp, byte[] reference) {
		this(type, fee, creator, timestamp, reference, null);
	}

	// Getters/setters

	public TransactionType getType() {
		return this.type;
	}

	public PublicKeyAccount getCreator() {
		return this.creator;
	}

	public long getTimestamp() {
		return this.timestamp;
	}

	public byte[] getReference() {
		return this.reference;
	}

	public BigDecimal getFee() {
		return this.fee;
	}

	public byte[] getSignature() {
		return this.signature;
	}

	// More information

	public long getDeadline() {
		// 24 hour deadline to include transaction in a block
		return this.timestamp + (24 * 60 * 60 * 1000);
	}

	/**
	 * Return length of byte[] if {@link Transactions#toBytes()} is called.
	 * <p>
	 * Used to allocate byte[]s or during serialization.
	 * 
	 * @return length of serialized transaction
	 */
	public abstract int getDataLength();

	public boolean hasMinimumFee() {
		return this.fee.compareTo(MINIMUM_FEE) >= 0;
	}

	public BigDecimal feePerByte() {
		return this.fee.divide(new BigDecimal(this.getDataLength()), MathContext.DECIMAL32);
	}

	public boolean hasMinimumFeePerByte() {
		return this.feePerByte().compareTo(minFeePerByte) >= 0;
	}

	public BigDecimal calcRecommendedFee() {
		BigDecimal recommendedFee = BigDecimal.valueOf(this.getDataLength()).divide(maxBytePerFee, MathContext.DECIMAL32).setScale(8);

		// security margin
		recommendedFee = recommendedFee.add(new BigDecimal("0.0000001"));

		if (recommendedFee.compareTo(MINIMUM_FEE) <= 0) {
			recommendedFee = MINIMUM_FEE;
		} else {
			recommendedFee = recommendedFee.setScale(0, BigDecimal.ROUND_UP);
		}

		return recommendedFee.setScale(8);
	}

	public static int getVersionByTimestamp(long timestamp) {
		if (timestamp < Block.POWFIX_RELEASE_TIMESTAMP) {
			return 1;
		} else {
			return 3;
		}
	}

	/**
	 * Get block height for this transaction in the blockchain.
	 * 
	 * @return height, or 0 if not in blockchain (i.e. unconfirmed)
	 * @throws SQLException
	 */
	public int getHeight() throws SQLException {
		if (this.signature == null)
			return 0;

		BlockTransaction blockTx = BlockTransaction.fromTransactionSignature(this.signature);
		if (blockTx == null)
			return 0;

		return BlockChain.getBlockHeightFromSignature(blockTx.getBlockSignature());
	}

	/**
	 * Get number of confirmations for this transaction.
	 * 
	 * @return confirmation count, or 0 if not in blockchain (i.e. unconfirmed)
	 * @throws SQLException
	 */
	public int getConfirmations() throws SQLException {
		int ourHeight = this.getHeight();
		if (ourHeight == 0)
			return 0;

		int blockChainHeight = BlockChain.getHeight();
		return blockChainHeight - ourHeight + 1;
	}

	// Load/Save/Delete

	// Typically called by sub-class' load-from-DB constructors

	/**
	 * Load base Transaction from DB using signature.
	 * <p>
	 * Note that the transaction type is <b>not</b> checked against the DB's value.
	 * 
	 * @param type
	 * @param signature
	 * @throws NoDataFoundException
	 *             if no matching row found
	 * @throws SQLException
	 */
	protected Transaction(TransactionType type, byte[] signature) throws SQLException {
		ResultSet rs = DB.checkedExecute("SELECT reference, creator, creation, fee FROM Transactions WHERE signature = ? AND type = ?", signature, type.value);
		if (rs == null)
			throw new NoDataFoundException();

		this.type = type;
		this.reference = DB.getResultSetBytes(rs.getBinaryStream(1), REFERENCE_LENGTH);
		// Note: can't use CREATOR_LENGTH in case we encounter Genesis Account's short, 8-byte public key
		this.creator = new PublicKeyAccount(DB.getResultSetBytes(rs.getBinaryStream(2)));
		this.timestamp = rs.getTimestamp(3).getTime();
		this.fee = rs.getBigDecimal(4).setScale(8);
		this.signature = signature;
	}

	protected void save(Connection connection) throws SQLException {
		SaveHelper saveHelper = new SaveHelper(connection, "Transactions");
		saveHelper.bind("signature", this.signature).bind("reference", this.reference).bind("type", this.type.value)
				.bind("creator", this.creator.getPublicKey()).bind("creation", new Timestamp(this.timestamp)).bind("fee", this.fee)
				.bind("milestone_block", null);
		saveHelper.execute();
	}

	protected void delete(Connection connection) throws SQLException {
		// NOTE: The corresponding row in sub-table is deleted automatically by the database thanks to "ON DELETE CASCADE" in the sub-table's FOREIGN KEY
		// definition.
		DB.checkedExecute("DELETE FROM Transactions WHERE signature = ?", this.signature);
	}

	// Navigation

	/**
	 * Load encapsulating Block from DB, if any
	 * 
	 * @return Block, or null if transaction is not in a Block
	 * @throws SQLException
	 */
	public Block getBlock() throws SQLException {
		if (this.signature == null)
			return null;

		BlockTransaction blockTx = BlockTransaction.fromTransactionSignature(this.signature);
		if (blockTx == null)
			return null;

		return Block.fromSignature(blockTx.getBlockSignature());
	}

	/**
	 * Load parent Transaction from DB via this transaction's reference.
	 * 
	 * @return Transaction, or null if no parent found (which should not happen)
	 * @throws SQLException
	 */
	public Transaction getParent() throws SQLException {
		if (this.reference == null)
			return null;

		return TransactionFactory.fromSignature(this.reference);
	}

	/**
	 * Load child Transaction from DB, if any.
	 * 
	 * @return Transaction, or null if no child found
	 * @throws SQLException
	 */
	public Transaction getChild() throws SQLException {
		if (this.signature == null)
			return null;

		return TransactionFactory.fromReference(this.signature);
	}

	// Converters

	/**
	 * Deserialize a byte[] into corresponding Transaction subclass.
	 * 
	 * @param data
	 * @return subclass of Transaction, e.g. PaymentTransaction
	 * @throws ParseException
	 */
	public static Transaction parse(byte[] data) throws ParseException {
		if (data == null)
			return null;

		if (data.length < TYPE_LENGTH)
			throw new ParseException("Byte data too short to determine transaction type");

		ByteBuffer byteBuffer = ByteBuffer.wrap(data);

		TransactionType type = TransactionType.valueOf(byteBuffer.getInt());
		if (type == null)
			return null;

		switch (type) {
			case GENESIS:
				return GenesisTransaction.parse(byteBuffer);

			case PAYMENT:
				return PaymentTransaction.parse(byteBuffer);

			case MESSAGE:
				return MessageTransaction.parse(byteBuffer);

			default:
				return null;
		}
	}

	public abstract JSONObject toJSON() throws SQLException;

	/**
	 * Produce JSON representation of common/base Transaction info.
	 * 
	 * @return JSONObject
	 * @throws SQLException
	 */
	@SuppressWarnings("unchecked")
	protected JSONObject getBaseJSON() throws SQLException {
		JSONObject json = new JSONObject();

		json.put("type", this.type.value);
		json.put("fee", this.fee.toPlainString());
		json.put("timestamp", this.timestamp);
		json.put("signature", Base58.encode(this.signature));

		if (this.reference != null)
			json.put("reference", Base58.encode(this.reference));

		json.put("confirmations", this.getConfirmations());

		return json;
	}

	/**
	 * Serialize transaction as byte[], including signature.
	 * 
	 * @return byte[]
	 */
	public abstract byte[] toBytes();

	/**
	 * Serialize transaction as byte[], stripping off trailing signature.
	 * <p>
	 * Used by signature-related methods such as {@link Transaction#calcSignature(PrivateKeyAccount)} and {@link Transaction#isSignatureValid()}
	 * 
	 * @return byte[]
	 */
	private byte[] toBytesLessSignature() {
		byte[] bytes = this.toBytes();
		return Arrays.copyOf(bytes, bytes.length - SIGNATURE_LENGTH);
	}

	// Processing

	public byte[] calcSignature(PrivateKeyAccount signer) {
		return signer.sign(this.toBytesLessSignature());
	}

	public boolean isSignatureValid() {
		if (this.signature == null)
			return false;

		return this.creator.verify(this.signature, this.toBytesLessSignature());
	}

	/**
	 * Returns whether transaction can be added to the blockchain.
	 * <p>
	 * Checks if transaction can have {@link Transaction#process(Connection)} called.
	 * <p>
	 * Expected to be called within an ongoing SQL Transaction, typically by {@link Block#process(Connection)}, hence the need for the Connection parameter.
	 * <p>
	 * Transactions that have already been processed will return false.
	 * 
	 * @param connection
	 * @return true if transaction can be processed, false otherwise
	 * @throws SQLException
	 */
	public abstract ValidationResult isValid(Connection connection) throws SQLException;

	/**
	 * Actually process a transaction, updating the blockchain.
	 * <p>
	 * Processes transaction, updating balances, references, assets, etc. as appropriate.
	 * <p>
	 * Expected to be called within an ongoing SQL Transaction, typically by {@link Block#process(Connection)}, hence the need for the Connection parameter.
	 * 
	 * @param connection
	 * @throws SQLException
	 */
	public abstract void process(Connection connection) throws SQLException;

	/**
	 * Undo transaction, updating the blockchain.
	 * <p>
	 * Undoes transaction, updating balances, references, assets, etc. as appropriate.
	 * <p>
	 * Expected to be called within an ongoing SQL Transaction, typically by {@link Block#process(Connection)}, hence the need for the Connection parameter.
	 * 
	 * @param connection
	 * @throws SQLException
	 */
	public abstract void orphan(Connection connection) throws SQLException;

}
