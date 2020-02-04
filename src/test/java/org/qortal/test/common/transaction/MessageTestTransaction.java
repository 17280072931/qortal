package org.qortal.test.common.transaction;

import java.math.BigDecimal;

import org.qortal.account.PrivateKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;

public class MessageTestTransaction extends TestTransaction {

	public static TransactionData randomTransaction(Repository repository, PrivateKeyAccount account, boolean wantValid) throws DataException {
		final int version = 3;
		String recipient = account.getAddress();
		final long assetId = Asset.QORT;
		BigDecimal amount = BigDecimal.valueOf(123L);
		byte[] data = "message contents".getBytes();
		final boolean isText = true;
		final boolean isEncrypted = false;

		return new MessageTransactionData(generateBase(account), version, recipient, assetId, amount, data, isText, isEncrypted);
	}

}
