package org.qora.transaction;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.qora.account.Account;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.crypto.Crypto;
import org.qora.data.transaction.GroupKickTransactionData;
import org.qora.data.group.GroupData;
import org.qora.data.transaction.TransactionData;
import org.qora.group.Group;
import org.qora.repository.DataException;
import org.qora.repository.GroupRepository;
import org.qora.repository.Repository;

import com.google.common.base.Utf8;

public class GroupKickTransaction extends Transaction {

	// Properties
	private GroupKickTransactionData groupKickTransactionData;

	// Constructors

	public GroupKickTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.groupKickTransactionData = (GroupKickTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.emptyList();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getAdmin().getAddress()))
			return true;

		if (address.equals(this.getMember().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getAdmin().getAddress()))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	public Account getAdmin() throws DataException {
		return new PublicKeyAccount(this.repository, this.groupKickTransactionData.getAdminPublicKey());
	}

	public Account getMember() throws DataException {
		return new Account(this.repository, this.groupKickTransactionData.getMember());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		GroupRepository groupRepository = this.repository.getGroupRepository();
		String groupName = groupKickTransactionData.getGroupName();
		
		// Check member address is valid
		if (!Crypto.isValidAddress(groupKickTransactionData.getMember()))
			return ValidationResult.INVALID_ADDRESS;

		// Check group name size bounds
		int groupNameLength = Utf8.encodedLength(groupName);
		if (groupNameLength < 1 || groupNameLength > Group.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check group name is lowercase
		if (!groupName.equals(groupName.toLowerCase()))
			return ValidationResult.NAME_NOT_LOWER_CASE;

		GroupData groupData = groupRepository.fromGroupName(groupName);

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account admin = getAdmin();
		Account member = getMember();

		// Can't kick if not an admin
		if (!groupRepository.adminExists(groupName, admin.getAddress()))
			return ValidationResult.NOT_GROUP_ADMIN;

		// Check member actually in group
		if (!groupRepository.memberExists(groupName, member.getAddress()))
			return ValidationResult.NOT_GROUP_MEMBER;

		// Can't kick another admin unless the group owner
		if (!admin.getAddress().equals(groupData.getOwner()) && groupRepository.adminExists(groupName, member.getAddress()))
			return ValidationResult.INVALID_GROUP_OWNER;

		// Check fee is positive
		if (groupKickTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		if (!Arrays.equals(admin.getLastReference(), groupKickTransactionData.getReference()))
			return ValidationResult.INVALID_REFERENCE;

		// Check creator has enough funds
		if (admin.getConfirmedBalance(Asset.QORA).compareTo(groupKickTransactionData.getFee()) < 0)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, groupKickTransactionData.getGroupName());
		group.kick(groupKickTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(groupKickTransactionData);

		// Update admin's balance
		Account admin = getAdmin();
		admin.setConfirmedBalance(Asset.QORA, admin.getConfirmedBalance(Asset.QORA).subtract(groupKickTransactionData.getFee()));

		// Update admin's reference
		admin.setLastReference(groupKickTransactionData.getSignature());
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, groupKickTransactionData.getGroupName());
		group.unkick(groupKickTransactionData);

		// Delete this transaction itself
		this.repository.getTransactionRepository().delete(groupKickTransactionData);

		// Update admin's balance
		Account admin = getAdmin();
		admin.setConfirmedBalance(Asset.QORA, admin.getConfirmedBalance(Asset.QORA).add(groupKickTransactionData.getFee()));

		// Update admin's reference
		admin.setLastReference(groupKickTransactionData.getReference());
	}

}
