package org.qortal.crosschain;

import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.CheckpointManager;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.StoredBlock;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.listeners.BlocksDownloadedEventListener;
import org.bitcoinj.core.listeners.NewBestBlockListener;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletTransaction;
import org.bitcoinj.wallet.WalletTransaction.Pool;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.bitcoinj.wallet.listeners.WalletCoinsSentEventListener;
import org.qortal.settings.Settings;

public class BTC {

	private static final MessageDigest RIPE_MD160_DIGESTER;
	private static final MessageDigest SHA256_DIGESTER;
	static {
		try {
			RIPE_MD160_DIGESTER = MessageDigest.getInstance("RIPEMD160");
			SHA256_DIGESTER = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	protected static final Logger LOGGER = LogManager.getLogger(BTC.class);

	private static BTC instance;

	private final NetworkParameters params;
	private final String checkpointsFileName;
	private final File directory;

	private PeerGroup peerGroup;
	private BlockStore blockStore;
	private BlockChain chain;

	private static class UpdateableCheckpointManager extends CheckpointManager implements NewBestBlockListener {
		private static final long CHECKPOINT_THRESHOLD = 7 * 24 * 60 * 60; // seconds

		private static final String MINIMAL_TESTNET3_TEXTFILE = "TXT CHECKPOINTS 1\n0\n1\nAAAAAAAAB+EH4QfhAAAH4AEAAAApmwX6UCEnJcYIKTa7HO3pFkqqNhAzJVBMdEuGAAAAAPSAvVCBUypCbBW/OqU0oIF7ISF84h2spOqHrFCWN9Zw6r6/T///AB0E5oOO\n";
		private static final String MINIMAL_MAINNET_TEXTFILE = "TXT CHECKPOINTS 1\n0\n1\nAAAAAAAAB+EH4QfhAAAH4AEAAABjl7tqvU/FIcDT9gcbVlA4nwtFUbxAtOawZzBpAAAAAKzkcK7NqciBjI/ldojNKncrWleVSgDfBCCn3VRrbSxXaw5/Sf//AB0z8Bkv\n";

		public UpdateableCheckpointManager(NetworkParameters params) throws IOException {
			super(params, getMinimalTextFileStream(params));
		}

		public UpdateableCheckpointManager(NetworkParameters params, InputStream inputStream) throws IOException {
			super(params, inputStream);
		}

		private static ByteArrayInputStream getMinimalTextFileStream(NetworkParameters params) {
			if (params == MainNetParams.get())
				return new ByteArrayInputStream(MINIMAL_MAINNET_TEXTFILE.getBytes());

			if (params == TestNet3Params.get())
				return new ByteArrayInputStream(MINIMAL_TESTNET3_TEXTFILE.getBytes());

			throw new RuntimeException("Failed to construct empty UpdateableCheckpointManageer");
		}

		@Override
		public void notifyNewBestBlock(StoredBlock block) {
			final int height = block.getHeight();

			if (height % this.params.getInterval() != 0)
				return;

			final long blockTimestamp = block.getHeader().getTimeSeconds();
			final long now = System.currentTimeMillis() / 1000L;
			if (blockTimestamp > now - CHECKPOINT_THRESHOLD)
				return; // Too recent

			LOGGER.trace(() -> String.format("Checkpointing at block %d dated %s", height, LocalDateTime.ofInstant(Instant.ofEpochSecond(blockTimestamp), ZoneOffset.UTC)));
			this.checkpoints.put(blockTimestamp, block);

			try {
				this.saveAsText(new File(BTC.getInstance().getDirectory(), BTC.getInstance().getCheckpointsFileName()));
			} catch (FileNotFoundException e) {
				// Save failed - log it but it's not critical
				LOGGER.warn("Failed to save updated BTC checkpoints: " + e.getMessage());
			}
		}

		public void saveAsText(File textFile) throws FileNotFoundException {
			try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(textFile), StandardCharsets.US_ASCII))) {
				writer.println("TXT CHECKPOINTS 1");
				writer.println("0"); // Number of signatures to read. Do this later.
				writer.println(this.checkpoints.size());

				ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);

				for (StoredBlock block : this.checkpoints.values()) {
					block.serializeCompact(buffer);
					writer.println(CheckpointManager.BASE64.encode(buffer.array()));
					buffer.position(0);
				}
			}
		}

		@SuppressWarnings("unused")
		public void saveAsBinary(File file) throws IOException {
			try (final FileOutputStream fileOutputStream = new FileOutputStream(file, false)) {
				MessageDigest digest = Sha256Hash.newDigest();

				try (final DigestOutputStream digestOutputStream = new DigestOutputStream(fileOutputStream, digest)) {
					digestOutputStream.on(false);

					try (final DataOutputStream dataOutputStream = new DataOutputStream(digestOutputStream)) {
						dataOutputStream.writeBytes("CHECKPOINTS 1");
						dataOutputStream.writeInt(0); // Number of signatures to read. Do this later.
						digestOutputStream.on(true);
						dataOutputStream.writeInt(this.checkpoints.size());

						ByteBuffer buffer = ByteBuffer.allocate(StoredBlock.COMPACT_SERIALIZED_SIZE);

						for (StoredBlock block : this.checkpoints.values()) {
							block.serializeCompact(buffer);
							dataOutputStream.write(buffer.array());
							buffer.position(0);
						}
					}
				}
			}
		}
	}
	private UpdateableCheckpointManager manager;

	// Constructors and instance

	private BTC() {
		if (Settings.getInstance().useBitcoinTestNet()) {
			/*
			this.params = RegTestParams.get();
			this.checkpointsFileName = "checkpoints-regtest.txt";
			*/
			this.params = TestNet3Params.get();
			this.checkpointsFileName = "checkpoints-testnet.txt";
		} else {
			this.params = MainNetParams.get();
			this.checkpointsFileName = "checkpoints.txt";
		}

		this.directory = new File("Qortal-BTC");

		if (!this.directory.exists())
			this.directory.mkdirs();

		File checkpointsFile = new File(this.directory, this.checkpointsFileName);
		try (InputStream checkpointsStream = new FileInputStream(checkpointsFile)) {
			this.manager = new UpdateableCheckpointManager(this.params, checkpointsStream);
		} catch (FileNotFoundException e) {
			// Construct with no checkpoints then
			try {
				this.manager = new UpdateableCheckpointManager(this.params);
			} catch (IOException e2) {
				throw new RuntimeException("Failed to create new BTC checkpoints", e2);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to load BTC checkpoints", e);
		}
	}

	public static synchronized BTC getInstance() {
		if (instance == null)
			instance = new BTC();

		return instance;
	}

	// Getters & setters

	/* package */ File getDirectory() {
		return this.directory;
	}

	/* package */ String getCheckpointsFileName() {
		return this.checkpointsFileName;
	}

	/* package */ NetworkParameters getNetworkParameters() {
		return this.params;
	}

	// Static utility methods

	public static byte[] hash160(byte[] message) {
		return RIPE_MD160_DIGESTER.digest(SHA256_DIGESTER.digest(message));
	}

	// Start-up & shutdown
	private void start(long startTime) throws BlockStoreException {
		StoredBlock checkpoint = this.manager.getCheckpointBefore(startTime - 1);

		this.blockStore = new MemoryBlockStore(params);
		this.blockStore.put(checkpoint);
		this.blockStore.setChainHead(checkpoint);

		this.chain = new BlockChain(this.params, this.blockStore);

		this.peerGroup = new PeerGroup(this.params, this.chain);
		this.peerGroup.setUserAgent("qortal", "1.0");

		if (this.params != RegTestParams.get()) {
			this.peerGroup.addPeerDiscovery(new DnsDiscovery(this.params));
		} else {
			peerGroup.addAddress(PeerAddress.localhost(this.params));
		}

		this.peerGroup.start();
	}

	private void stop() {
		this.peerGroup.stop();
	}

	// Utility methods

	protected Wallet createEmptyWallet() {
		return Wallet.createBasic(this.params);
	}

	private class ReplayHooks {
		private Runnable preReplay;
		private Runnable postReplay;

		public ReplayHooks(Runnable preReplay, Runnable postReplay) {
			this.preReplay = preReplay;
			this.postReplay = postReplay;
		}

		public void preReplay() {
			this.preReplay.run();
		}

		public void postReplay() {
			this.postReplay.run();
		}
	}

	private void replayChain(long startTime, Wallet wallet, ReplayHooks replayHooks) throws BlockStoreException {
		this.start(startTime);

		final WalletCoinsReceivedEventListener coinsReceivedListener = (someWallet, tx, prevBalance, newBalance) -> {
			LOGGER.debug(String.format("Wallet-related transaction %s", tx.getTxId()));
		};

		final WalletCoinsSentEventListener coinsSentListener = (someWallet, tx, prevBalance, newBalance) -> {
			LOGGER.debug(String.format("Wallet-related transaction %s", tx.getTxId()));
		};

		if (wallet != null) {
			wallet.addCoinsReceivedEventListener(coinsReceivedListener);
			wallet.addCoinsSentEventListener(coinsSentListener);

			// Link wallet to chain and peerGroup
			this.chain.addWallet(wallet);
			this.peerGroup.addWallet(wallet);
		}

		try {
			if (replayHooks != null)
				replayHooks.preReplay();

			// Sync blockchain using peerGroup, skipping as much as we can before startTime
			this.peerGroup.setFastCatchupTimeSecs(startTime);
			this.chain.addNewBestBlockListener(Threading.SAME_THREAD, this.manager);
			this.peerGroup.downloadBlockChain();
		} finally {
			// Clean up
			if (replayHooks != null)
				replayHooks.postReplay();

			if (wallet != null) {
				wallet.removeCoinsReceivedEventListener(coinsReceivedListener);
				wallet.removeCoinsSentEventListener(coinsSentListener);

				this.peerGroup.removeWallet(wallet);
				this.chain.removeWallet(wallet);
			}

			this.stop();
		}
	}

	private void replayChain(long startTime) throws BlockStoreException {
		this.replayChain(startTime, null, null);
	}

	// Actual useful methods for use by other classes

	/** Returns median timestamp from latest 11 blocks, in seconds. */
	public Long getMedianBlockTime() {
		// 11 blocks, at roughly 10 minutes per block, means we should go back at least 110 minutes
		// but some blocks have been way longer than 10 minutes, so be massively pessimistic
		long startTime = (System.currentTimeMillis() / 1000L) - 11 * 60 * 60; // 11 hours before now, in seconds

		try {
			replayChain(startTime);

			List<StoredBlock> latestBlocks = new ArrayList<>(11);
			StoredBlock block = this.blockStore.getChainHead();
			for (int i = 0; i < 11; ++i) {
				latestBlocks.add(block);
				block = block.getPrev(this.blockStore);
			}

			// Descending, but order shouldn't matter as we're picking median...
			latestBlocks.sort((a, b) -> Long.compare(b.getHeader().getTimeSeconds(), a.getHeader().getTimeSeconds()));

			return latestBlocks.get(5).getHeader().getTimeSeconds();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("BTC blockstore issue: %s", e.getMessage()));
			return null;
		}
	}

	public Coin getBalance(String base58Address, long startTime) {
		// Create new wallet containing only the address we're interested in, ignoring anything prior to startTime
		Wallet wallet = createEmptyWallet();
		Address address = Address.fromString(this.params, base58Address);
		wallet.addWatchedAddress(address, startTime);

		try {
			replayChain(startTime, wallet, null);

			// Now that blockchain is up-to-date, return current balance
			return wallet.getBalance();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("BTC blockstore issue: %s", e.getMessage()));
			return null;
		}
	}

	public List<TransactionOutput> getOutputs(String base58Address, long startTime) {
		Wallet wallet = createEmptyWallet();
		Address address = Address.fromString(this.params, base58Address);
		wallet.addWatchedAddress(address, startTime);

		try {
			replayChain(startTime, wallet, null);

			// Now that blockchain is up-to-date, return outputs
			return wallet.getWatchedOutputs(true);
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("BTC blockstore issue: %s", e.getMessage()));
			return null;
		}
	}

	private static class TransactionStorage {
		private Transaction transaction;

		public void store(Transaction transaction) {
			this.transaction = transaction;
		}

		public Transaction getTransaction() {
			return this.transaction;
		}
	}

	public List<TransactionOutput> getOutputs(byte[] txId, long startTime) {
		Wallet wallet = createEmptyWallet();

		// Add random address to wallet
		ECKey fakeKey = new ECKey();
		wallet.addWatchedAddress(Address.fromKey(this.params, fakeKey, ScriptType.P2PKH), startTime);

		final Sha256Hash txHash = Sha256Hash.wrap(txId);

		final TransactionStorage transactionStorage = new TransactionStorage();

		final BlocksDownloadedEventListener listener = (peer, block, filteredBlock, blocksLeft) -> {
			List<Transaction> transactions = block.getTransactions();

			if (transactions == null)
				return;

			for (Transaction transaction : transactions)
				if (transaction.getTxId().equals(txHash)) {
					System.out.println(String.format("We downloaded block containing tx!"));
					transactionStorage.store(transaction);
				}
		};

		ReplayHooks replayHooks = new ReplayHooks(() -> this.peerGroup.addBlocksDownloadedEventListener(listener), () -> this.peerGroup.removeBlocksDownloadedEventListener(listener));
		
		// Replay chain in the hope it will download transactionId as a dependency
		try {
			replayChain(startTime, wallet, replayHooks);

			Transaction realTx = transactionStorage.getTransaction();
			return realTx.getOutputs();
		} catch (BlockStoreException e) {
			LOGGER.error(String.format("BTC blockstore issue: %s", e.getMessage()));
			return null;
		}
	}

}
