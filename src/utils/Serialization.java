package utils;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;

import qora.account.PublicKeyAccount;
import qora.transaction.Transaction;

public class Serialization {

	/**
	 * Convert BigDecimal, unscaled, to byte[] then prepend with zero bytes to fixed length of 8.
	 * 
	 * @param amount
	 * @return byte[8]
	 */
	public static byte[] serializeBigDecimal(BigDecimal amount) {
		byte[] amountBytes = amount.unscaledValue().toByteArray();
		byte[] output = new byte[8];
		System.arraycopy(amountBytes, 0, output, 8 - amountBytes.length, amountBytes.length);
		return output;
	}

	public static BigDecimal deserializeBigDecimal(ByteBuffer byteBuffer) {
		byte[] bytes = new byte[8];
		byteBuffer.get(bytes);
		return new BigDecimal(new BigInteger(bytes), 8);
	}

	public static String deserializeRecipient(ByteBuffer byteBuffer) {
		byte[] bytes = new byte[Transaction.RECIPIENT_LENGTH];
		byteBuffer.get(bytes);
		return Base58.encode(bytes);
	}

	public static PublicKeyAccount deserializePublicKey(ByteBuffer byteBuffer) {
		byte[] bytes = new byte[Transaction.CREATOR_LENGTH];
		byteBuffer.get(bytes);
		return new PublicKeyAccount(bytes);
	}

	public static String deserializeSizedString(ByteBuffer byteBuffer, int maxSize) throws ParseException {
		int size = byteBuffer.getInt();
		if (size > maxSize || size > byteBuffer.remaining())
			throw new ParseException("Serialized string too long");

		byte[] bytes = new byte[size];
		byteBuffer.get(bytes);

		try {
			return new String(bytes, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new ParseException("UTF-8 charset unsupported during string deserialization");
		}
	}

}
