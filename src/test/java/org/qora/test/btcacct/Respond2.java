package org.qora.test.btcacct;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.account.PrivateKeyAccount;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.controller.Controller;
import org.qora.crosschain.BTC;
import org.qora.crosschain.BTCACCT;
import org.qora.crypto.Crypto;
import org.qora.data.transaction.BaseTransactionData;
import org.qora.data.transaction.DeployAtTransactionData;
import org.qora.data.transaction.TransactionData;
import org.qora.group.Group;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.transaction.DeployAtTransaction;
import org.qora.transaction.Transaction;
import org.qora.utils.Base58;

import com.google.common.hash.HashCode;

/**
 * Initiator must be Qora-chain so that initiator can send initial message to BTC P2SH then Qora can scan for P2SH add send corresponding message to Qora AT.
 *
 * Initiator (wants Qora, has BTC)
 * 		Funds BTC P2SH address
 * 
 * Responder (has Qora, wants BTC)
 * 		Builds Qora ACCT AT and funds it with Qora
 * 
 * Initiator sends recipient+secret+script as input to BTC P2SH address, releasing BTC amount - fees to responder
 * 
 * Qora nodes scan for P2SH output, checks amount and recipient and if ok sends secret to Qora ACCT AT
 * (Or it's possible to feed BTC transaction details into Qora AT so it can check them itself?)
 * 
 * Qora ACCT AT sends its Qora to initiator
 *
 */

public class Respond2 {

	private static final long REFUND_TIMEOUT = 600L; // seconds

	private static void usage() {
		System.err.println(String.format("usage: Respond2 <your-QORT-PRIVkey> <your-BTC-pubkey> <QORT-amount> <BTC-amount> <their-QORT-pubkey> <their-BTC-pubkey> <hash-of-secret> <locktime> <P2SH-address>"));
		System.err.println(String.format("example: Respond2 pYQ6DpQBJ2n72TCLJLScEvwhf3boxWy2kQEPynakwpj \\\n"
				+ "\t03aa20871c2195361f2826c7a649eab6b42639630c4d8c33c55311d5c1e476b5d6 \\\n"
				+ "\t123 0.00008642 \\\n"
				+ "\tJBNBQQDzZsm5do1BrwWAp53Ps4KYJVt749EGpCf7ofte \\\n"
				+ "\t032783606be32a3e639a33afe2b15f058708ab124f3b290d595ee954390a0c8559 \\\n"
				+ "\te43f5ab106b70df2e85656de30e1862891752f81e82f5dfd03abb8465a7346f9 1574441679 2N4R2pSEzLcJgtgAbFuLvviwwEkBrmq6sx4"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length != 9)
			usage();

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		NetworkParameters params = TestNet3Params.get();

		int argIndex = 0;
		String yourQortPrivKey58 = args[argIndex++];
		String yourBitcoinPubKeyHex = args[argIndex++];

		String rawQortAmount = args[argIndex++];
		String rawBitcoinAmount = args[argIndex++];

		String theirQortPubKey58 = args[argIndex++];
		String theirBitcoinPubKeyHex = args[argIndex++];

		String secretHashHex = args[argIndex++];
		String rawLockTime = args[argIndex++];
		String rawP2shAddress = args[argIndex++];

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			byte[] yourQortPrivKey = Base58.decode(yourQortPrivKey58);
			PrivateKeyAccount yourQortalAccount = new PrivateKeyAccount(repository, yourQortPrivKey);
			byte[] yourQortPubKey = yourQortalAccount.getPublicKey();
			System.out.println(String.format("Your Qortal address: %s", yourQortalAccount.getAddress()));

			byte[] yourBitcoinPubKey = HashCode.fromString(yourBitcoinPubKeyHex).asBytes();
			ECKey yourBitcoinKey = ECKey.fromPublicOnly(yourBitcoinPubKey);
			Address yourBitcoinAddress = Address.fromKey(params, yourBitcoinKey, ScriptType.P2PKH);
			System.out.println(String.format("Your Bitcoin address: %s", yourBitcoinAddress.toString()));

			byte[] theirQortPubKey = Base58.decode(theirQortPubKey58);
			PublicKeyAccount theirQortalAccount = new PublicKeyAccount(repository, theirQortPubKey);
			System.out.println(String.format("Their Qortal address: %s", theirQortalAccount.getAddress()));

			byte[] theirBitcoinPubKey = HashCode.fromString(theirBitcoinPubKeyHex).asBytes();
			ECKey theirBitcoinKey = ECKey.fromPublicOnly(theirBitcoinPubKey);
			Address theirBitcoinAddress = Address.fromKey(params, theirBitcoinKey, ScriptType.P2PKH);
			System.out.println(String.format("Their Bitcoin address: %s", theirBitcoinAddress.toString()));

			System.out.println("Hash of secret: " + secretHashHex);

			// New/derived info

			System.out.println("\nCHECKING info from other party:");

			int lockTime = Integer.valueOf(rawLockTime);
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneId.systemDefault()).toString(), lockTime));

			byte[] secretHash = HashCode.fromString(secretHashHex).asBytes();
			System.out.println("Hash of secret: " + HashCode.fromBytes(secretHash).toString());

			byte[] redeemScriptBytes = BTCACCT.buildRedeemScript(secretHash, theirBitcoinPubKey, yourBitcoinPubKey, lockTime);
			System.out.println("Redeem script: " + HashCode.fromBytes(redeemScriptBytes).toString());

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			System.out.println("P2SH address: " + p2shAddress.toString());

			if (!p2shAddress.toString().equals(rawP2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", p2shAddress.toString(), rawP2shAddress));
				System.exit(2);
			}

			// TODO: Check for funded P2SH


			System.out.println("\nYour response:");

			// If good, deploy AT
			byte[] creationBytes = BTCACCT.buildCiyamAT(secretHash, theirQortPubKey, REFUND_TIMEOUT / 60);
			System.out.println("CIYAM AT creation bytes: " + HashCode.fromBytes(creationBytes).toString());

			BigDecimal qortAmount = new BigDecimal(rawQortAmount).setScale(8);

			long txTimestamp = System.currentTimeMillis();
			byte[] lastReference = yourQortalAccount.getLastReference();

			if (lastReference == null) {
				System.err.println(String.format("Qortal account %s has no last reference", yourQortalAccount.getAddress()));
				System.exit(2);
			}

			BigDecimal fee = BigDecimal.ZERO;
			BaseTransactionData baseTransactionData = new BaseTransactionData(txTimestamp, Group.NO_GROUP, lastReference, yourQortPubKey, fee, null);
			TransactionData deployAtTransactionData = new DeployAtTransactionData(baseTransactionData, "QORT-BTC", "QORT-BTC ACCT", "", "", creationBytes, qortAmount, Asset.QORT);

			Transaction deployAtTransaction = new DeployAtTransaction(repository, deployAtTransactionData);

			fee = deployAtTransaction.calcRecommendedFee();
			deployAtTransactionData.setFee(fee);

			deployAtTransaction.sign(yourQortalAccount);
		} catch (NumberFormatException e) {
			usage();
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
