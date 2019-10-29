package org.qora.test.naming;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.util.Random;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.qora.account.PrivateKeyAccount;
import org.qora.block.BlockMinter;
import org.qora.data.naming.NameData;
import org.qora.data.transaction.BuyNameTransactionData;
import org.qora.data.transaction.RegisterNameTransactionData;
import org.qora.data.transaction.SellNameTransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryManager;
import org.qora.test.common.BlockUtils;
import org.qora.test.common.Common;
import org.qora.test.common.TransactionUtils;
import org.qora.test.common.transaction.TestTransaction;

public class OrphaningTests extends Common {

	protected static final Random random = new Random();

	private Repository repository;
	private PrivateKeyAccount alice;
	private PrivateKeyAccount bob;
	private String name;
	private BigDecimal price;

	@Before
	public void beforeTest() throws DataException {
		Common.useDefaultSettings();

		repository = RepositoryManager.getRepository();
		alice = Common.getTestAccount(repository, "alice");
		bob = Common.getTestAccount(repository, "bob");

		name = "test name" + " " + random.nextInt(1_000_000);
		price = new BigDecimal(random.nextInt(1000)).setScale(8);
	}

	@After
	public void afterTest() throws DataException {
		name = null;
		price = null;

		alice = null;
		bob = null;
		repository = null;

		Common.orphanCheck();
	}

	@Test
	public void testRegisterName() throws DataException {
		// Register-name
		RegisterNameTransactionData transactionData = new RegisterNameTransactionData(TestTransaction.generateBase(alice), alice.getAddress(), name, "{}");
		TransactionUtils.signAndMint(repository, transactionData, alice);

		String name = transactionData.getName();

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Orphan register-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer exists
		assertFalse(repository.getNameRepository().nameExists(name));

		// Re-process register-name
		BlockMinter.mintTestingBlock(repository, alice);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));
	}

	@Test
	public void testSellName() throws DataException {
		// Register-name
		testRegisterName();

		// Sell-name
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(alice), name, price);
		TransactionUtils.signAndMint(repository, transactionData, alice);

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", price, nameData.getSalePrice());

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.getIsForSale());
		// Not concerned about price

		// Re-process sell-name
		BlockMinter.mintTestingBlock(repository, alice);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", price, nameData.getSalePrice());

		// Orphan sell-name and register-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name no longer exists
		assertFalse(repository.getNameRepository().nameExists(name));
		nameData = repository.getNameRepository().fromName(name);
		assertNull(nameData);

		// Re-process register-name and sell-name
		BlockMinter.mintTestingBlock(repository, alice);
		// Unconfirmed sell-name transaction not included in previous block
		// as it isn't valid until name exists thanks to register-name transaction.
		BlockMinter.mintTestingBlock(repository, alice);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", price, nameData.getSalePrice());
	}

	@Test
	public void testBuyName() throws DataException {
		// Register-name and sell-name
		testSellName();

		String seller = alice.getAddress();

		// Buy-name
		BuyNameTransactionData transactionData = new BuyNameTransactionData(TestTransaction.generateBase(bob), name, price, seller);
		TransactionUtils.signAndMint(repository, transactionData, bob);

		NameData nameData;

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.getIsForSale());
		// Not concerned about price

		// Orphan buy-name
		BlockUtils.orphanLastBlock(repository);

		// Check name is for sale (not sold)
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", price, nameData.getSalePrice());

		// Re-process buy-name
		BlockMinter.mintTestingBlock(repository, alice);

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.getIsForSale());
		// Not concerned about price
		assertEquals(bob.getAddress(), nameData.getOwner());

		// Orphan buy-name and sell-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.getIsForSale());
		// Not concerned about price
		assertEquals(alice.getAddress(), nameData.getOwner());

		// Re-process sell-name and buy-name
		BlockMinter.mintTestingBlock(repository, alice);
		// Unconfirmed buy-name transaction not included in previous block
		// as it isn't valid until name is for sale thanks to sell-name transaction.
		BlockMinter.mintTestingBlock(repository, alice);

		// Check name is sold
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.getIsForSale());
		// Not concerned about price
		assertEquals(bob.getAddress(), nameData.getOwner());
	}

	@Test
	public void testSellBuySellName() throws DataException {
		// Register-name, sell-name, buy-name
		testBuyName();

		// Sell-name
		BigDecimal newPrice = new BigDecimal(random.nextInt(1000)).setScale(8);
		SellNameTransactionData transactionData = new SellNameTransactionData(TestTransaction.generateBase(bob), name, newPrice);
		TransactionUtils.signAndMint(repository, transactionData, bob);

		NameData nameData;

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name
		BlockUtils.orphanLastBlock(repository);

		// Check name no longer for sale
		nameData = repository.getNameRepository().fromName(name);
		assertFalse(nameData.getIsForSale());
		// Not concerned about price

		// Re-process sell-name
		BlockMinter.mintTestingBlock(repository, alice);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", newPrice, nameData.getSalePrice());

		// Orphan sell-name and buy-name
		BlockUtils.orphanBlocks(repository, 2);

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		// Note: original sale price
		assertEqualBigDecimals("price incorrect", price, nameData.getSalePrice());
		assertEquals(alice.getAddress(), nameData.getOwner());

		// Re-process buy-name and sell-name
		BlockMinter.mintTestingBlock(repository, bob);
		// Unconfirmed sell-name transaction not included in previous block
		// as it isn't valid until name owned by bob thanks to buy-name transaction.
		BlockMinter.mintTestingBlock(repository, bob);

		// Check name does exist
		assertTrue(repository.getNameRepository().nameExists(name));

		// Check name is for sale
		nameData = repository.getNameRepository().fromName(name);
		assertTrue(nameData.getIsForSale());
		assertEqualBigDecimals("price incorrect", newPrice, nameData.getSalePrice());
		assertEquals(bob.getAddress(), nameData.getOwner());
	}

}
