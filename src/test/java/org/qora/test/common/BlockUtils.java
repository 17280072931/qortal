package org.qora.test.common;

import java.math.BigDecimal;

import org.qora.account.PrivateKeyAccount;
import org.qora.block.Block;
import org.qora.block.BlockChain;
import org.qora.block.BlockMinter;
import org.qora.data.block.BlockData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

public class BlockUtils {

	/** Mints a new block using "alice-reward-share" test account. */
	public static void mintBlock(Repository repository) throws DataException {
		PrivateKeyAccount mintingAccount = Common.getTestAccount(repository, "alice-reward-share");
		BlockMinter.mintTestingBlock(repository, mintingAccount);
	}

	public static BigDecimal getNextBlockReward(Repository repository) throws DataException {
		int currentHeight = repository.getBlockRepository().getBlockchainHeight();

		return BlockChain.getInstance().getRewardAtHeight(currentHeight + 1);
	}

	public static void orphanLastBlock(Repository repository) throws DataException {
		BlockData blockData = repository.getBlockRepository().getLastBlock();
		Block block = new Block(repository, blockData);
		block.orphan();
		repository.saveChanges();
	}

	public static void orphanBlocks(Repository repository, int count) throws DataException {
		for (int i = 0; i < count; ++i)
			orphanLastBlock(repository);
	}

}
