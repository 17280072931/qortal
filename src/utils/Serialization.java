package utils;

import java.math.BigDecimal;

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

}
