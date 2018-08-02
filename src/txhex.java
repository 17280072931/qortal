import com.google.common.hash.HashCode;

import data.transaction.TransactionData;
import qora.block.BlockChain;
import repository.DataException;
import repository.Repository;
import repository.RepositoryManager;
import transform.TransformationException;
import transform.transaction.TransactionTransformer;
import utils.Base58;

public class txhex {

	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("usage: txhex <base58-tx-signature>");
			System.exit(1);
		}

		byte[] signature = Base58.decode(args[0]);

		try {
			test.Common.setRepository();
		} catch (DataException e) {
			System.err.println("Couldn't connect to repository: " + e.getMessage());
			System.exit(2);
		}

		try {
			BlockChain.validate();
		} catch (DataException e) {
			System.err.println("Couldn't validate repository: " + e.getMessage());
			System.exit(2);
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			TransactionData transactionData = repository.getTransactionRepository().fromSignature(signature);
			byte[] bytes = TransactionTransformer.toBytes(transactionData);
			System.out.println(HashCode.fromBytes(bytes).toString());
		} catch (DataException | TransformationException e) {
			e.printStackTrace();
		}

		try {
			test.Common.closeRepository();
		} catch (DataException e) {
			e.printStackTrace();
		}
	}

}
