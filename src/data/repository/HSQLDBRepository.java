package data.repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import data.block.Block;
import data.block.IBlockData;
import database.DB;
import qora.account.PublicKeyAccount;

public class HSQLDBRepository implements IRepository
{
	protected static final int TRANSACTIONS_SIGNATURE_LENGTH = 64;
	protected static final int GENERATOR_SIGNATURE_LENGTH = 64;
	protected static final int REFERENCE_LENGTH = GENERATOR_SIGNATURE_LENGTH + TRANSACTIONS_SIGNATURE_LENGTH;

	private static final String BLOCK_DB_COLUMNS = "version, reference, transaction_count, total_fees, "
			+ "transactions_signature, height, generation, generating_balance, generator, generator_signature, AT_data, AT_fees";

	public IBlockData getBlockBySignature(byte[] signature) throws SQLException
	{		
		ResultSet rs = DB.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE signature = ?", signature);
		return getBlockFromResultSet(rs);
	}

	public IBlockData getBlockByHeight(int height) throws SQLException
	{		
		ResultSet rs = DB.checkedExecute("SELECT " + BLOCK_DB_COLUMNS + " FROM Blocks WHERE height = ?", height);
		return getBlockFromResultSet(rs);
	}

	private IBlockData getBlockFromResultSet(ResultSet rs) throws SQLException {
		int version = rs.getInt(1);
		byte[] reference = DB.getResultSetBytes(rs.getBinaryStream(2), REFERENCE_LENGTH);
		int transactionCount = rs.getInt(3);
		BigDecimal totalFees = rs.getBigDecimal(4);
		byte[] transactionsSignature = DB.getResultSetBytes(rs.getBinaryStream(5), TRANSACTIONS_SIGNATURE_LENGTH);
		int height = rs.getInt(6);
		long timestamp = rs.getTimestamp(7).getTime();
		BigDecimal generatingBalance = rs.getBigDecimal(8);
		byte[] generatorPublicKey = DB.getResultSetBytes(rs.getBinaryStream(9));
		byte[] generatorSignature = DB.getResultSetBytes(rs.getBinaryStream(10), GENERATOR_SIGNATURE_LENGTH);
		byte[] atBytes = DB.getResultSetBytes(rs.getBinaryStream(11));
		BigDecimal atFees = rs.getBigDecimal(12);

		return new Block(version, reference, transactionCount, totalFees, transactionsSignature, height, timestamp,
				generatingBalance,generatorPublicKey, generatorSignature, atBytes, atFees);
	}
}
