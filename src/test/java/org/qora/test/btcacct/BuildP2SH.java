package org.qora.test.btcacct;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.controller.Controller;
import org.qora.crosschain.BTC;
import org.qora.crosschain.BTCACCT;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.settings.Settings;

import com.google.common.hash.HashCode;

public class BuildP2SH {

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: BuildP2SH <refund-BTC-P2PKH> <BTC-amount> <redeem-BTC-P2PKH> <HASH160-of-secret> <locktime> (<BTC-redeem/refund-fee>)"));
		System.err.println(String.format("example: BuildP2SH "
				+ "mrTDPdM15cFWJC4g223BXX5snicfVJBx6M \\\n"
				+ "\t0.00008642 \\\n"
				+ "\tn2N5VKrzq39nmuefZwp3wBiF4icdXX2B6o \\\n"
				+ "\td1b64100879ad93ceaa3c15929b6fe8550f54967 \\\n"
				+ "\t1585920000"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 5 || args.length > 6)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Settings.fileInstance("settings-test.json");

		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		Address refundBitcoinAddress = null;
		Coin bitcoinAmount = null;
		Address redeemBitcoinAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;
		Coin bitcoinFee = BTCACCT.DEFAULT_BTC_FEE;

		int argIndex = 0;
		try {
			refundBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (refundBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Refund BTC address must be in P2PKH form");

			bitcoinAmount = Coin.parseCoin(args[argIndex++]);

			redeemBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Redeem BTC address must be in P2PKH form");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("Hash of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);
			int refundTimeoutDelay = lockTime - (int) (System.currentTimeMillis() / 1000L);
			if (refundTimeoutDelay < 600 || refundTimeoutDelay > 7 * 24 * 60 * 60)
				usage("Locktime (seconds) should be at between 10 minutes and 1 week from now");

			if (args.length > argIndex)
				bitcoinFee = Coin.parseCoin(args[argIndex++]);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			System.out.println(String.format("Refund Bitcoin address: %s", refundBitcoinAddress));
			System.out.println(String.format("Bitcoin redeem amount: %s", bitcoinAmount.toPlainString()));

			System.out.println(String.format("Redeem Bitcoin address: %s", redeemBitcoinAddress));
			System.out.println(String.format("Redeem miner's fee: %s", BTC.FORMAT.format(bitcoinFee)));

			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
			System.out.println(String.format("Hash of secret: %s", HashCode.fromBytes(secretHash)));

			byte[] redeemScriptBytes = BTCACCT.buildScript(refundBitcoinAddress.getHash(), lockTime, redeemBitcoinAddress.getHash(), secretHash);
			System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);

			Address p2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);
			System.out.println(String.format("P2SH address: %s", p2shAddress));

			bitcoinAmount = bitcoinAmount.add(bitcoinFee);

			// Fund P2SH
			System.out.println(String.format("\nYou need to fund %s with %s (includes redeem/refund fee of %s)",
					p2shAddress.toString(), BTC.FORMAT.format(bitcoinAmount), BTC.FORMAT.format(bitcoinFee)));

			System.out.println("Once this is done, responder should run Respond to check P2SH funding and create AT");
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
