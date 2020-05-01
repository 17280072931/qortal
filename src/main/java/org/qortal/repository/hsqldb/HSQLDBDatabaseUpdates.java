package org.qortal.repository.hsqldb;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.block.BlockChain;

public class HSQLDBDatabaseUpdates {

	private static final Logger LOGGER = LogManager.getLogger(HSQLDBDatabaseUpdates.class);

	/**
	 * Apply any incremental changes to database schema.
	 * 
	 * @throws SQLException
	 */
	public static void updateDatabase(Connection connection) throws SQLException {
		while (databaseUpdating(connection))
			incrementDatabaseVersion(connection);
	}

	/**
	 * Increment database's schema version.
	 * 
	 * @throws SQLException
	 */
	private static void incrementDatabaseVersion(Connection connection) throws SQLException {
		try (Statement stmt = connection.createStatement()) {
			stmt.execute("UPDATE DatabaseInfo SET version = version + 1");
			connection.commit();
		}
	}

	/**
	 * Fetch current version of database schema.
	 * 
	 * @return int, 0 if no schema yet
	 * @throws SQLException
	 */
	private static int fetchDatabaseVersion(Connection connection) throws SQLException {
		int databaseVersion = 0;

		try (Statement stmt = connection.createStatement()) {
			if (stmt.execute("SELECT version FROM DatabaseInfo"))
				try (ResultSet resultSet = stmt.getResultSet()) {
					if (resultSet.next())
						databaseVersion = resultSet.getInt(1);
				}
		} catch (SQLException e) {
			// empty database
		}

		return databaseVersion;
	}

	/**
	 * Incrementally update database schema, returning whether an update happened.
	 * 
	 * @return true - if a schema update happened, false otherwise
	 * @throws SQLException
	 */
	private static boolean databaseUpdating(Connection connection) throws SQLException {
		int databaseVersion = fetchDatabaseVersion(connection);

		try (Statement stmt = connection.createStatement()) {

			/*
			 * Try not to add too many constraints as much of these checks will be performed during transaction validation. Also some constraints might be too
			 * harsh on competing unconfirmed transactions.
			 * 
			 * Only really add "ON DELETE CASCADE" to sub-tables that store type-specific data. For example on sub-types of Transactions like
			 * PaymentTransactions. A counterexample would be adding "ON DELETE CASCADE" to Assets using Assets' "reference" as a foreign key referring to
			 * Transactions' "signature". We want to database to automatically delete complete transaction data (Transactions row and corresponding
			 * PaymentTransactions row), but leave deleting less related table rows (Assets) to the Java logic.
			 */

			switch (databaseVersion) {
				case 0:
					// create from new
					// FYI: "UCC" in HSQLDB means "upper-case comparison", i.e. case-insensitive
					stmt.execute("SET DATABASE SQL NAMES TRUE"); // SQL keywords cannot be used as DB object names, e.g. table names
					stmt.execute("SET DATABASE SQL SYNTAX MYS TRUE"); // Required for our use of INSERT ... ON DUPLICATE KEY UPDATE ... syntax
					stmt.execute("SET DATABASE SQL RESTRICT EXEC TRUE"); // No multiple-statement execute() or DDL/DML executeQuery()
					stmt.execute("SET DATABASE TRANSACTION CONTROL MVCC"); // Use MVCC over default two-phase locking, a-k-a "LOCKS"
					stmt.execute("SET DATABASE DEFAULT TABLE TYPE CACHED");
					stmt.execute("SET DATABASE COLLATION SQL_TEXT NO PAD"); // Do not pad strings to same length before comparison
					stmt.execute("CREATE COLLATION SQL_TEXT_UCC_NO_PAD FOR SQL_TEXT FROM SQL_TEXT_UCC NO PAD");
					stmt.execute("CREATE COLLATION SQL_TEXT_NO_PAD FOR SQL_TEXT FROM SQL_TEXT NO PAD");
					stmt.execute("SET FILES SPACE TRUE"); // Enable per-table block space within .data file, useful for CACHED table types
					stmt.execute("SET FILES LOB SCALE 1"); // LOB granularity is 1KB
					stmt.execute("CREATE TABLE DatabaseInfo ( version INTEGER NOT NULL )");
					stmt.execute("INSERT INTO DatabaseInfo VALUES ( 0 )");
					stmt.execute("CREATE TYPE BlockSignature AS VARBINARY(128)");
					stmt.execute("CREATE TYPE Signature AS VARBINARY(64)");
					stmt.execute("CREATE TYPE QortalAddress AS VARCHAR(36)");
					stmt.execute("CREATE TYPE QortalPublicKey AS VARBINARY(32)");
					stmt.execute("CREATE TYPE QortalAmount AS BIGINT");
					stmt.execute("CREATE TYPE GenericDescription AS VARCHAR(4000)");
					stmt.execute("CREATE TYPE RegisteredName AS VARCHAR(128) COLLATE SQL_TEXT_NO_PAD");
					stmt.execute("CREATE TYPE NameData AS VARCHAR(4000)");
					stmt.execute("CREATE TYPE MessageData AS VARBINARY(4000)");
					stmt.execute("CREATE TYPE PollName AS VARCHAR(128) COLLATE SQL_TEXT_NO_PAD");
					stmt.execute("CREATE TYPE PollOption AS VARCHAR(80) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE PollOptionIndex AS INTEGER");
					stmt.execute("CREATE TYPE DataHash AS VARBINARY(32)");
					stmt.execute("CREATE TYPE AssetID AS BIGINT");
					stmt.execute("CREATE TYPE AssetName AS VARCHAR(34) COLLATE SQL_TEXT_NO_PAD");
					stmt.execute("CREATE TYPE AssetOrderID AS VARBINARY(64)");
					stmt.execute("CREATE TYPE ATName AS VARCHAR(32) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE ATType AS VARCHAR(32) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE ATTags AS VARCHAR(80) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE ATCode AS BLOB(64K)"); // 16bit * 1
					stmt.execute("CREATE TYPE ATState AS BLOB(1M)"); // 16bit * 8 + 16bit * 4 + 16bit * 4
					stmt.execute("CREATE TYPE ATCreationBytes AS BLOB(576K)"); // 16bit * 1 + 16bit * 8
					stmt.execute("CREATE TYPE ATStateHash as VARBINARY(32)");
					stmt.execute("CREATE TYPE ATMessage AS VARBINARY(256)");
					stmt.execute("CREATE TYPE GroupID AS INTEGER");
					stmt.execute("CREATE TYPE GroupName AS VARCHAR(400) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE GroupReason AS VARCHAR(128) COLLATE SQL_TEXT_UCC_NO_PAD");
					stmt.execute("CREATE TYPE RewardSharePercent AS INT");
					break;

				case 1:
					// Blocks
					stmt.execute("CREATE TABLE Blocks (signature BlockSignature, version TINYINT NOT NULL, reference BlockSignature, "
							+ "transaction_count INTEGER NOT NULL, total_fees QortalAmount NOT NULL, transactions_signature Signature NOT NULL, "
							+ "height INTEGER NOT NULL, generation TIMESTAMP WITH TIME ZONE NOT NULL, "
							+ "generator QortalPublicKey NOT NULL, generator_signature Signature NOT NULL, AT_count INTEGER NOT NULL, AT_fees QortalAmount NOT NULL, "
							+ "PRIMARY KEY (signature))");
					// For finding blocks by height.
					stmt.execute("CREATE INDEX BlockHeightIndex ON Blocks (height)");
					// For finding blocks by the account that generated them.
					stmt.execute("CREATE INDEX BlockGeneratorIndex ON Blocks (generator)");
					// For finding blocks by reference, e.g. child blocks.
					stmt.execute("CREATE INDEX BlockReferenceIndex ON Blocks (reference)");
					// For finding blocks by generation timestamp or finding height of latest block immediately before generation timestamp, etc.
					stmt.execute("CREATE INDEX BlockGenerationHeightIndex ON Blocks (generation, height)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE Blocks NEW SPACE");
					break;

				case 2:
					// Generic transactions (null reference, creator and milestone_block for genesis transactions)
					stmt.execute("CREATE TABLE Transactions (signature Signature, reference Signature, type TINYINT NOT NULL, "
							+ "creator QortalPublicKey NOT NULL, creation TIMESTAMP WITH TIME ZONE NOT NULL, fee QortalAmount NOT NULL, milestone_block BlockSignature, "
							+ "PRIMARY KEY (signature))");
					// For finding transactions by transaction type.
					stmt.execute("CREATE INDEX TransactionTypeIndex ON Transactions (type)");
					// For finding transactions using creation timestamp.
					stmt.execute("CREATE INDEX TransactionCreationIndex ON Transactions (creation)");
					// For when a user wants to lookup ALL transactions they have created, with optional type.
					stmt.execute("CREATE INDEX TransactionCreatorIndex ON Transactions (creator, type)");
					// For finding transactions by reference, e.g. child transactions.
					stmt.execute("CREATE INDEX TransactionReferenceIndex ON Transactions (reference)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE Transactions NEW SPACE");

					// Transaction-Block mapping ("transaction_signature" is unique as a transaction cannot be included in more than one block)
					stmt.execute("CREATE TABLE BlockTransactions (block_signature BlockSignature, sequence INTEGER, transaction_signature Signature UNIQUE, "
							+ "PRIMARY KEY (block_signature, sequence), FOREIGN KEY (transaction_signature) REFERENCES Transactions (signature) ON DELETE CASCADE, "
							+ "FOREIGN KEY (block_signature) REFERENCES Blocks (signature) ON DELETE CASCADE)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE BlockTransactions NEW SPACE");

					// Unconfirmed transactions
					// We use this as searching for transactions with no corresponding mapping in BlockTransactions is much slower.
					stmt.execute("CREATE TABLE UnconfirmedTransactions (signature Signature PRIMARY KEY, creation TIMESTAMP WITH TIME ZONE NOT NULL)");
					// Index to allow quick sorting by creation-else-signature
					stmt.execute("CREATE INDEX UnconfirmedTransactionsIndex ON UnconfirmedTransactions (creation, signature)");

					// Transaction participants
					// To allow lookup of all activity by an address
					stmt.execute("CREATE TABLE TransactionParticipants (signature Signature, participant QortalAddress NOT NULL, "
							+ "FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Use a separate table space as this table will be very large.
					stmt.execute("SET TABLE TransactionParticipants NEW SPACE");
					break;

				case 3:
					// Genesis Transactions
					stmt.execute("CREATE TABLE GenesisTransactions (signature Signature, recipient QortalAddress NOT NULL, "
							+ "amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, PRIMARY KEY (signature), "
							+ "FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 4:
					// Payment Transactions
					stmt.execute("CREATE TABLE PaymentTransactions (signature Signature, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "amount QortalAmount NOT NULL, PRIMARY KEY (signature), "
							+ "FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 5:
					// Register Name Transactions
					stmt.execute("CREATE TABLE RegisterNameTransactions (signature Signature, registrant QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "owner QortalAddress NOT NULL, data NameData NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 6:
					// Update Name Transactions
					stmt.execute("CREATE TABLE UpdateNameTransactions (signature Signature, owner QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "new_owner QortalAddress NOT NULL, new_data NameData NOT NULL, name_reference Signature, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 7:
					// Sell Name Transactions
					stmt.execute("CREATE TABLE SellNameTransactions (signature Signature, owner QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "amount QortalAmount NOT NULL, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 8:
					// Cancel Sell Name Transactions
					stmt.execute("CREATE TABLE CancelSellNameTransactions (signature Signature, owner QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 9:
					// Buy Name Transactions
					stmt.execute("CREATE TABLE BuyNameTransactions (signature Signature, buyer QortalPublicKey NOT NULL, name RegisteredName NOT NULL, "
							+ "seller QortalAddress NOT NULL, amount QortalAmount NOT NULL, name_reference Signature, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 10:
					// Create Poll Transactions
					stmt.execute("CREATE TABLE CreatePollTransactions (signature Signature, creator QortalPublicKey NOT NULL, owner QortalAddress NOT NULL, "
							+ "poll_name PollName NOT NULL, description GenericDescription NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Poll options. NB: option is implicitly NON NULL and UNIQUE due to being part of compound primary key
					stmt.execute("CREATE TABLE CreatePollTransactionOptions (signature Signature, option_index TINYINT NOT NULL, option_name PollOption, "
							+ "PRIMARY KEY (signature, option_index), FOREIGN KEY (signature) REFERENCES CreatePollTransactions (signature) ON DELETE CASCADE)");
					// For the future: add flag to polls to allow one or multiple votes per voter
					break;

				case 11:
					// Vote On Poll Transactions
					stmt.execute("CREATE TABLE VoteOnPollTransactions (signature Signature, voter QortalPublicKey NOT NULL, poll_name PollName NOT NULL, "
							+ "option_index PollOptionIndex NOT NULL, previous_option_index PollOptionIndex, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 12:
					// Arbitrary/Multi-payment/Message/Payment Transaction Payments
					stmt.execute("CREATE TABLE SharedTransactionPayments (signature Signature, recipient QortalAddress NOT NULL, "
							+ "amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, "
							+ "PRIMARY KEY (signature, recipient, asset_id), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 13:
					// Arbitrary Transactions
					stmt.execute("CREATE TABLE ArbitraryTransactions (signature Signature, sender QortalPublicKey NOT NULL, version TINYINT NOT NULL, "
							+ "service TINYINT NOT NULL, data_hash DataHash NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// NB: Actual data payload stored elsewhere
					// For the future: data payload should be encrypted, at the very least with transaction's reference as the seed for the encryption key
					break;

				case 14:
					// Issue Asset Transactions
					stmt.execute(
							"CREATE TABLE IssueAssetTransactions (signature Signature, issuer QortalPublicKey NOT NULL, owner QortalAddress NOT NULL, asset_name AssetName NOT NULL, "
									+ "description GenericDescription NOT NULL, quantity BIGINT NOT NULL, is_divisible BOOLEAN NOT NULL, asset_id AssetID, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// For the future: maybe convert quantity from BIGINT to QortalAmount, regardless of divisibility
					break;

				case 15:
					// Transfer Asset Transactions
					stmt.execute("CREATE TABLE TransferAssetTransactions (signature Signature, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "asset_id AssetID NOT NULL, amount QortalAmount NOT NULL,"
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 16:
					// Create Asset Order Transactions
					stmt.execute("CREATE TABLE CreateAssetOrderTransactions (signature Signature, creator QortalPublicKey NOT NULL, "
							+ "have_asset_id AssetID NOT NULL, amount QortalAmount NOT NULL, want_asset_id AssetID NOT NULL, price QortalAmount NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 17:
					// Cancel Asset Order Transactions
					stmt.execute("CREATE TABLE CancelAssetOrderTransactions (signature Signature, creator QortalPublicKey NOT NULL, "
							+ "asset_order_id AssetOrderID NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 18:
					// Multi-payment Transactions
					stmt.execute("CREATE TABLE MultiPaymentTransactions (signature Signature, sender QortalPublicKey NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 19:
					// Deploy CIYAM AT Transactions
					stmt.execute("CREATE TABLE DeployATTransactions (signature Signature, creator QortalPublicKey NOT NULL, AT_name ATName NOT NULL, "
							+ "description GenericDescription NOT NULL, AT_type ATType NOT NULL, AT_tags ATTags NOT NULL, "
							+ "creation_bytes ATCreationBytes NOT NULL, amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, AT_address QortalAddress, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// For looking up the Deploy AT Transaction based on deployed AT address
					stmt.execute("CREATE INDEX DeployATAddressIndex on DeployATTransactions (AT_address)");
					break;

				case 20:
					// Message Transactions
					stmt.execute(
							"CREATE TABLE MessageTransactions (signature Signature, version TINYINT NOT NULL, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
									+ "is_text BOOLEAN NOT NULL, is_encrypted BOOLEAN NOT NULL, amount QortalAmount NOT NULL, asset_id AssetID NOT NULL, data MessageData NOT NULL, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 21:
					// Assets (including QORT coin itself)
					stmt.execute("CREATE TABLE Assets (asset_id AssetID, owner QortalAddress NOT NULL, "
							+ "asset_name AssetName NOT NULL, description GenericDescription NOT NULL, "
							+ "quantity BIGINT NOT NULL, is_divisible BOOLEAN NOT NULL, reference Signature NOT NULL, PRIMARY KEY (asset_id))");
					// We need a corresponding trigger to make sure new asset_id values are assigned sequentially start from 0
					stmt.execute(
							"CREATE TRIGGER Asset_ID_Trigger BEFORE INSERT ON Assets REFERENCING NEW ROW AS new_row FOR EACH ROW WHEN (new_row.asset_id IS NULL) "
									+ "SET new_row.asset_id = (SELECT IFNULL(MAX(asset_id) + 1, 0) FROM Assets)");
					// For when a user wants to lookup an asset by name
					stmt.execute("CREATE INDEX AssetNameIndex on Assets (asset_name)");
					break;

				case 22:
					// Accounts
					stmt.execute("CREATE TABLE Accounts (account QortalAddress, reference Signature, public_key QortalPublicKey, PRIMARY KEY (account))");
					stmt.execute("CREATE TABLE AccountBalances (account QortalAddress, asset_id AssetID, balance QortalAmount NOT NULL, "
							+ "PRIMARY KEY (account, asset_id), FOREIGN KEY (account) REFERENCES Accounts (account) ON DELETE CASCADE)");
					// For looking up an account by public key
					stmt.execute("CREATE INDEX AccountPublicKeyIndex on Accounts (public_key)");
					break;

				case 23:
					// Asset Orders
					stmt.execute(
							"CREATE TABLE AssetOrders (asset_order_id AssetOrderID, creator QortalPublicKey NOT NULL, have_asset_id AssetID NOT NULL, want_asset_id AssetID NOT NULL, "
									+ "amount QortalAmount NOT NULL, fulfilled QortalAmount NOT NULL, price QortalAmount NOT NULL, "
									+ "ordered TIMESTAMP WITH TIME ZONE NOT NULL, is_closed BOOLEAN NOT NULL, is_fulfilled BOOLEAN NOT NULL, "
									+ "PRIMARY KEY (asset_order_id))");
					// For quick matching of orders. is_closed are is_fulfilled included so inactive orders can be filtered out.
					stmt.execute("CREATE INDEX AssetOrderMatchingIndex on AssetOrders (have_asset_id, want_asset_id, is_closed, is_fulfilled, price, ordered)");
					// For when a user wants to look up their current/historic orders. is_closed included so user can filter by active/inactive orders.
					stmt.execute("CREATE INDEX AssetOrderCreatorIndex on AssetOrders (creator, is_closed)");
					break;

				case 24:
					// Asset Trades
					stmt.execute("CREATE TABLE AssetTrades (initiating_order_id AssetOrderId NOT NULL, target_order_id AssetOrderId NOT NULL, "
							+ "amount QortalAmount NOT NULL, price QortalAmount NOT NULL, traded TIMESTAMP WITH TIME ZONE NOT NULL)");
					// For looking up historic trades based on orders
					stmt.execute("CREATE INDEX AssetTradeBuyOrderIndex on AssetTrades (initiating_order_id, traded)");
					stmt.execute("CREATE INDEX AssetTradeSellOrderIndex on AssetTrades (target_order_id, traded)");
					break;

				case 25:
					// Polls/Voting
					stmt.execute(
							"CREATE TABLE Polls (poll_name PollName, description GenericDescription NOT NULL, creator QortalPublicKey NOT NULL, owner QortalAddress NOT NULL, "
									+ "published TIMESTAMP WITH TIME ZONE NOT NULL, " + "PRIMARY KEY (poll_name))");
					// Various options available on a poll
					stmt.execute("CREATE TABLE PollOptions (poll_name PollName, option_index TINYINT NOT NULL, option_name PollOption, "
							+ "PRIMARY KEY (poll_name, option_index), FOREIGN KEY (poll_name) REFERENCES Polls (poll_name) ON DELETE CASCADE)");
					// Actual votes cast on a poll by voting users. NOTE: only one vote per user supported at this time.
					stmt.execute("CREATE TABLE PollVotes (poll_name PollName, voter QortalPublicKey, option_index PollOptionIndex NOT NULL, "
							+ "PRIMARY KEY (poll_name, voter), FOREIGN KEY (poll_name) REFERENCES Polls (poll_name) ON DELETE CASCADE)");
					// For when a user wants to lookup poll they own
					stmt.execute("CREATE INDEX PollOwnerIndex on Polls (owner)");
					break;

				case 26:
					// Registered Names
					stmt.execute("CREATE TABLE Names (name RegisteredName, data NameData NOT NULL, owner QortalAddress NOT NULL, "
							+ "registered TIMESTAMP WITH TIME ZONE NOT NULL, updated TIMESTAMP WITH TIME ZONE, reference Signature, is_for_sale BOOLEAN NOT NULL, sale_price QortalAmount, "
							+ "PRIMARY KEY (name))");
					break;

				case 27:
					// CIYAM Automated Transactions
					stmt.execute(
							"CREATE TABLE ATs (AT_address QortalAddress, creator QortalPublicKey, creation TIMESTAMP WITH TIME ZONE, version INTEGER NOT NULL, "
									+ "asset_id AssetID NOT NULL, code_bytes ATCode NOT NULL, is_sleeping BOOLEAN NOT NULL, sleep_until_height INTEGER, "
									+ "is_finished BOOLEAN NOT NULL, had_fatal_error BOOLEAN NOT NULL, is_frozen BOOLEAN NOT NULL, frozen_balance QortalAmount, "
									+ "PRIMARY key (AT_address))");
					// For finding executable ATs, ordered by creation timestamp
					stmt.execute("CREATE INDEX ATIndex on ATs (is_finished, creation)");
					// For finding ATs by creator
					stmt.execute("CREATE INDEX ATCreatorIndex on ATs (creator)");

					// AT state on a per-block basis
					stmt.execute("CREATE TABLE ATStates (AT_address QortalAddress, height INTEGER NOT NULL, creation TIMESTAMP WITH TIME ZONE, "
							+ "state_data ATState, state_hash ATStateHash NOT NULL, fees QortalAmount NOT NULL, "
							+ "PRIMARY KEY (AT_address, height), FOREIGN KEY (AT_address) REFERENCES ATs (AT_address) ON DELETE CASCADE)");
					// For finding per-block AT states, ordered by creation timestamp
					stmt.execute("CREATE INDEX BlockATStateIndex on ATStates (height, creation)");

					// Generated AT Transactions
					stmt.execute(
							"CREATE TABLE ATTransactions (signature Signature, AT_address QortalAddress NOT NULL, recipient QortalAddress, amount QortalAmount, asset_id AssetID, message ATMessage, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// For finding AT Transactions generated by a specific AT
					stmt.execute("CREATE INDEX ATTransactionsIndex on ATTransactions (AT_address)");
					break;

				case 28:
					// Account groups
					stmt.execute(
							"CREATE TABLE Groups (group_id GroupID, owner QortalAddress NOT NULL, group_name GroupName, description GenericDescription NOT NULL, "
									+ "created TIMESTAMP WITH TIME ZONE NOT NULL, updated TIMESTAMP WITH TIME ZONE, is_open BOOLEAN NOT NULL, "
									+ "reference Signature, PRIMARY KEY (group_id))");
					// We need a corresponding trigger to make sure new group_id values are assigned sequentially starting from 1
					stmt.execute(
							"CREATE TRIGGER Group_ID_Trigger BEFORE INSERT ON Groups REFERENCING NEW ROW AS new_row FOR EACH ROW WHEN (new_row.group_id IS NULL) "
									+ "SET new_row.group_id = (SELECT IFNULL(MAX(group_id) + 1, 1) FROM Groups)");
					// For when a user wants to lookup an group by name
					stmt.execute("CREATE INDEX GroupNameIndex on Groups (group_name)");
					// For finding groups by owner
					stmt.execute("CREATE INDEX GroupOwnerIndex ON Groups (owner)");

					// Admins
					stmt.execute("CREATE TABLE GroupAdmins (group_id GroupID, admin QortalAddress, reference Signature NOT NULL, "
							+ "PRIMARY KEY (group_id, admin), FOREIGN KEY (group_id) REFERENCES Groups (group_id) ON DELETE CASCADE)");
					// For finding groups that address administrates
					stmt.execute("CREATE INDEX GroupAdminIndex ON GroupAdmins (admin)");

					// Members
					stmt.execute(
							"CREATE TABLE GroupMembers (group_id GroupID, address QortalAddress, joined TIMESTAMP WITH TIME ZONE NOT NULL, reference Signature NOT NULL, "
									+ "PRIMARY KEY (group_id, address), FOREIGN KEY (group_id) REFERENCES Groups (group_id) ON DELETE CASCADE)");
					// For finding groups that address is member
					stmt.execute("CREATE INDEX GroupMemberIndex ON GroupMembers (address)");

					// Invites
					stmt.execute("CREATE TABLE GroupInvites (group_id GroupID, inviter QortalAddress, invitee QortalAddress, "
							+ "expiry TIMESTAMP WITH TIME ZONE NOT NULL, reference Signature, "
							+ "PRIMARY KEY (group_id, invitee), FOREIGN KEY (group_id) REFERENCES Groups (group_id) ON DELETE CASCADE)");
					// For finding invites sent by inviter
					stmt.execute("CREATE INDEX GroupInviteInviterIndex ON GroupInvites (inviter)");
					// For finding invites by group
					stmt.execute("CREATE INDEX GroupInviteInviteeIndex ON GroupInvites (invitee)");
					// For expiry maintenance
					stmt.execute("CREATE INDEX GroupInviteExpiryIndex ON GroupInvites (expiry)");

					// Pending "join requests"
					stmt.execute(
							"CREATE TABLE GroupJoinRequests (group_id GroupID, joiner QortalAddress, reference Signature NOT NULL, PRIMARY KEY (group_id, joiner))");

					// Bans
					// NULL expiry means does not expire!
					stmt.execute(
							"CREATE TABLE GroupBans (group_id GroupID, offender QortalAddress, admin QortalAddress NOT NULL, banned TIMESTAMP WITH TIME ZONE NOT NULL, "
									+ "reason GenericDescription NOT NULL, expiry TIMESTAMP WITH TIME ZONE, reference Signature NOT NULL, "
									+ "PRIMARY KEY (group_id, offender), FOREIGN KEY (group_id) REFERENCES Groups (group_id) ON DELETE CASCADE)");
					// For expiry maintenance
					stmt.execute("CREATE INDEX GroupBanExpiryIndex ON GroupBans (expiry)");
					break;

				case 29:
					// Account group transactions
					stmt.execute("CREATE TABLE CreateGroupTransactions (signature Signature, creator QortalPublicKey NOT NULL, group_name GroupName NOT NULL, "
							+ "owner QortalAddress NOT NULL, description GenericDescription NOT NULL, is_open BOOLEAN NOT NULL, group_id GroupID, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					stmt.execute("CREATE TABLE UpdateGroupTransactions (signature Signature, owner QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "new_owner QortalAddress NOT NULL, new_description GenericDescription NOT NULL, new_is_open BOOLEAN NOT NULL, group_reference Signature, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Account group add/remove admin transactions
					stmt.execute(
							"CREATE TABLE AddGroupAdminTransactions (signature Signature, owner QortalPublicKey NOT NULL, group_id GroupID NOT NULL, address QortalAddress NOT NULL, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					stmt.execute(
							"CREATE TABLE RemoveGroupAdminTransactions (signature Signature, owner QortalPublicKey NOT NULL, group_id GroupID NOT NULL, admin QortalAddress NOT NULL, "
									+ "admin_reference Signature, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Account group join/leave transactions
					stmt.execute("CREATE TABLE JoinGroupTransactions (signature Signature, joiner QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "invite_reference Signature, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					stmt.execute("CREATE TABLE LeaveGroupTransactions (signature Signature, leaver QortalPublicKey NOT NULL, group_id GroupID NOT NULL, "
							+ "member_reference Signature, admin_reference Signature, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Account group kick transaction
					stmt.execute(
							"CREATE TABLE GroupKickTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, address QortalAddress NOT NULL, "
									+ "reason GroupReason, member_reference Signature, admin_reference Signature, join_reference Signature, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Account group invite/cancel-invite transactions
					stmt.execute(
							"CREATE TABLE GroupInviteTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, invitee QortalAddress NOT NULL, "
									+ "time_to_live INTEGER NOT NULL, join_reference Signature, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Cancel group invite
					stmt.execute(
							"CREATE TABLE CancelGroupInviteTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, invitee QortalAddress NOT NULL, "
									+ "invite_reference Signature, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Account ban/cancel-ban transactions
					stmt.execute(
							"CREATE TABLE GroupBanTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, address QortalAddress NOT NULL, "
									+ "reason GroupReason, time_to_live INTEGER NOT NULL, "
									+ "member_reference Signature, admin_reference Signature, join_invite_reference Signature, "
									+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					stmt.execute(
							"CREATE TABLE CancelGroupBanTransactions (signature Signature, admin QortalPublicKey NOT NULL, group_id GroupID NOT NULL, address QortalAddress NOT NULL, "
									+ "ban_reference Signature, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 30:
					// Networking
					stmt.execute("CREATE TABLE Peers (hostname VARCHAR(255), port INTEGER, last_connected TIMESTAMP WITH TIME ZONE, last_attempted TIMESTAMP WITH TIME ZONE, "
							+ "last_height INTEGER, last_misbehaved TIMESTAMP WITH TIME ZONE, PRIMARY KEY (hostname, port))");
					break;

				case 31:
					stmt.execute("SET DATABASE TRANSACTION CONTROL MVCC"); // Use MVCC over default two-phase locking, a-k-a "LOCKS"
					break;

				case 32:
					// Unified PeerAddress requires peer hostname & port stored as one string
					stmt.execute("ALTER TABLE Peers ALTER COLUMN hostname RENAME TO address");
					// Make sure literal IPv6 addresses are enclosed in square brackets.
					stmt.execute("UPDATE Peers SET address=CONCAT('[', address, ']') WHERE POSITION(':' IN address) != 0");
					stmt.execute("UPDATE Peers SET address=CONCAT(address, ':', port)");
					// We didn't name the PRIMARY KEY constraint when creating Peers table, so can't easily drop it
					// Workaround is to create a new table with new constraint, drop old table, then rename.
					stmt.execute("CREATE TABLE PeersTEMP AS (SELECT * FROM Peers) WITH DATA");
					stmt.execute("ALTER TABLE PeersTEMP DROP COLUMN port");
					stmt.execute("ALTER TABLE PeersTEMP ADD PRIMARY KEY (address)");
					stmt.execute("DROP TABLE Peers");
					stmt.execute("ALTER TABLE PeersTEMP RENAME TO Peers");
					break;

				case 33:
					// Add groupID to all transactions - groupID 0 is default, which means groupless/no-group
					stmt.execute("ALTER TABLE Transactions ADD COLUMN tx_group_id GroupID NOT NULL DEFAULT 0");
					stmt.execute("CREATE INDEX TransactionGroupIndex ON Transactions (tx_group_id)");

					// Adding approval to group-based transactions
					// Default approval threshold is 100% for existing groups but probably of no effect in production
					stmt.execute("ALTER TABLE Groups ADD COLUMN approval_threshold TINYINT NOT NULL DEFAULT 100 BEFORE reference");
					stmt.execute("ALTER TABLE CreateGroupTransactions ADD COLUMN approval_threshold TINYINT NOT NULL DEFAULT 100 BEFORE group_id");
					stmt.execute("ALTER TABLE UpdateGroupTransactions ADD COLUMN new_approval_threshold TINYINT NOT NULL DEFAULT 100 BEFORE group_reference");

					// Approval transactions themselves
					// "pending_signature" contains signature of pending transaction requiring approval
					// "prior_reference" contains signature of previous approval transaction for orphaning purposes
					stmt.execute("CREATE TABLE GroupApprovalTransactions (signature Signature, admin QortalPublicKey NOT NULL, pending_signature Signature NOT NULL, approval BOOLEAN NOT NULL, "
							+ "prior_reference Signature, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Accounts have a default groupID to be used if transaction's txGroupId is 0
					stmt.execute("ALTER TABLE Accounts add default_group_id GroupID NOT NULL DEFAULT 0");
					break;

				case 34:
					// SET_GROUP transaction support
					stmt.execute("CREATE TABLE SetGroupTransactions (signature Signature, default_group_id GroupID NOT NULL, previous_default_group_id GroupID, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 35:
					// Group-based transaction approval min/max block delay
					stmt.execute("ALTER TABLE Groups ADD COLUMN min_block_delay INT NOT NULL DEFAULT 0 BEFORE reference");
					stmt.execute("ALTER TABLE Groups ADD COLUMN max_block_delay INT NOT NULL DEFAULT 1440 BEFORE reference");
					stmt.execute("ALTER TABLE CreateGroupTransactions ADD COLUMN min_block_delay INT NOT NULL DEFAULT 0 BEFORE group_id");
					stmt.execute("ALTER TABLE CreateGroupTransactions ADD COLUMN max_block_delay INT NOT NULL DEFAULT 1440 BEFORE group_id");
					stmt.execute("ALTER TABLE UpdateGroupTransactions ADD COLUMN new_min_block_delay INT NOT NULL DEFAULT 0 BEFORE group_reference");
					stmt.execute("ALTER TABLE UpdateGroupTransactions ADD COLUMN new_max_block_delay INT NOT NULL DEFAULT 1440 BEFORE group_reference");
					break;

				case 36:
					// Adding group-ness to record types that could require approval for their related transactions
					// e.g. REGISTER_NAME might require approval and so Names table requires groupID
					// Registered Names
					stmt.execute("ALTER TABLE Names ADD COLUMN creation_group_id GroupID NOT NULL DEFAULT 0");
					// Assets aren't ever updated so don't need group-ness
					// for future use: stmt.execute("ALTER TABLE Assets ADD COLUMN creation_group_id GroupID NOT NULL DEFAULT 0");
					// Polls aren't ever updated, only voted upon using option index so don't need group-ness
					// for future use: stmt.execute("ALTER TABLE Polls ADD COLUMN creation_group_id GroupID NOT NULL DEFAULT 0");
					// CIYAM ATs
					stmt.execute("ALTER TABLE ATs ADD COLUMN creation_group_id GroupID NOT NULL DEFAULT 0");
					// Groups can be updated but updates require approval from original groupID
					stmt.execute("ALTER TABLE Groups ADD COLUMN creation_group_id GroupID NOT NULL DEFAULT 0");
					break;

				case 37:
					// Performance-improving INDEX
					stmt.execute("CREATE INDEX IF NOT EXISTS BlockGenerationHeightIndex ON Blocks (generation, height)");
					// Asset orders now have isClosed=true when isFulfilled=true
					stmt.execute("UPDATE AssetOrders SET is_closed = TRUE WHERE is_fulfilled = TRUE");
					break;

				case 38:
					// Rename asset trade columns for clarity
					stmt.execute("ALTER TABLE AssetTrades ALTER COLUMN amount RENAME TO target_amount");
					stmt.execute("ALTER TABLE AssetTrades ALTER COLUMN price RENAME TO initiator_amount");
					// Add support for asset "data" - typically JSON map like registered name data
					stmt.execute("CREATE TYPE AssetData AS VARCHAR(4000)");
					stmt.execute("ALTER TABLE Assets ADD data AssetData NOT NULL DEFAULT '' BEFORE reference");
					stmt.execute("ALTER TABLE Assets ADD creation_group_id GroupID NOT NULL DEFAULT 0 BEFORE reference");
					// Add support for asset "data" to ISSUE_ASSET transaction
					stmt.execute("ALTER TABLE IssueAssetTransactions ADD data AssetData NOT NULL DEFAULT '' BEFORE asset_id");
					// Add support for UPDATE_ASSET transactions
					stmt.execute("CREATE TABLE UpdateAssetTransactions (signature Signature, owner QortalPublicKey NOT NULL, asset_id AssetID NOT NULL, "
									+ "new_owner QortalAddress NOT NULL, new_description GenericDescription NOT NULL, new_data AssetData NOT NULL, "
									+ "orphan_reference Signature, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Correct Assets.reference to use ISSUE_ASSET transaction's signature instead of reference.
					// This is to help UPDATE_ASSET orphaning.
					stmt.execute("MERGE INTO Assets USING (SELECT asset_id, signature FROM Assets JOIN Transactions USING (reference) JOIN IssueAssetTransactions USING (signature)) AS Updates "
							+ "ON Assets.asset_id = Updates.asset_id WHEN MATCHED THEN UPDATE SET Assets.reference = Updates.signature");
					break;

				case 39:
					// Support for automatically setting joiner's default groupID when they join a group (by JOIN_GROUP or corresponding admin's INVITE_GROUP)
					stmt.execute("ALTER TABLE JoinGroupTransactions ADD previous_group_id INTEGER");
					stmt.execute("ALTER TABLE GroupInviteTransactions ADD previous_group_id INTEGER");
					// Ditto for leaving
					stmt.execute("ALTER TABLE LeaveGroupTransactions ADD previous_group_id INTEGER");
					stmt.execute("ALTER TABLE GroupKickTransactions ADD previous_group_id INTEGER");
					stmt.execute("ALTER TABLE GroupBanTransactions ADD previous_group_id INTEGER");
					break;

				case 40:
					// Increase asset "data" size from 4K to 400K
					stmt.execute("CREATE TYPE AssetDataLob AS CLOB(400K)");
					stmt.execute("ALTER TABLE Assets ALTER COLUMN data AssetDataLob");
					stmt.execute("ALTER TABLE IssueAssetTransactions ALTER COLUMN data AssetDataLob");
					stmt.execute("ALTER TABLE UpdateAssetTransactions ALTER COLUMN new_data AssetDataLob");
					break;

				case 41:
					// New asset pricing
					/*
					 * We store "unit price" for asset orders but need enough precision to accurately
					 * represent fractional values without loss.
					 * Asset quantities can be up to either 1_000_000_000_000_000_000 (19 digits) if indivisible,
					 * or 10_000_000_000.00000000 (11+8 = 19 digits) if divisible.
					 * Two 19-digit numbers need 38 integer and 38 fractional to cover extremes of unit price.
					 * However, we use another 10 more fractional digits to avoid rounding issues.
					 * 38 integer + 48 fractional gives 86, so: DECIMAL (86, 48)
					 */
					// Rename price to unit_price to preserve indexes
					stmt.execute("ALTER TABLE AssetOrders ALTER COLUMN price RENAME TO unit_price");
					// Adjust precision
					stmt.execute("ALTER TABLE AssetOrders ALTER COLUMN unit_price DECIMAL(76,48)");
					// Add want-amount column
					stmt.execute("ALTER TABLE AssetOrders ADD want_amount QortalAmount BEFORE unit_price");
					// Calculate want-amount values
					stmt.execute("UPDATE AssetOrders set want_amount = amount * unit_price");
					// want-amounts all set, so disallow NULL
					stmt.execute("ALTER TABLE AssetOrders ALTER COLUMN want_amount SET NOT NULL");
					// Rename corresponding column in CreateAssetOrderTransactions
					stmt.execute("ALTER TABLE CreateAssetOrderTransactions ALTER COLUMN price RENAME TO want_amount");
					break;

				case 42:
					// New asset pricing #2
					/*
					 *  Use "price" (discard want-amount) but enforce pricing units in one direction
					 *  to avoid all the reciprocal and round issues.
					 */
					stmt.execute("ALTER TABLE CreateAssetOrderTransactions ALTER COLUMN want_amount RENAME TO price");
					stmt.execute("ALTER TABLE AssetOrders DROP COLUMN want_amount");
					stmt.execute("ALTER TABLE AssetOrders ALTER COLUMN unit_price RENAME TO price");
					stmt.execute("ALTER TABLE AssetOrders ALTER COLUMN price QortalAmount");
					/*
					 *  Normalize any 'old' orders to 'new' pricing.
					 *  We must do this so that requesting open orders can be sorted by price.
					 */
					// Make sure new asset pricing timestamp (used below) is UTC
					stmt.execute("SET TIME ZONE INTERVAL '0:00' HOUR TO MINUTE");
					// Normalize amount/fulfilled to asset with highest assetID, BEFORE price correction
					stmt.execute("UPDATE AssetOrders SET amount = amount * price, fulfilled = fulfilled * price "
							+ "WHERE ordered < timestamp(" + 0 /* was BlockChain.getInstance().getNewAssetPricingTimestamp() */ + ") "
							+ "AND have_asset_id < want_asset_id");
					// Normalize price into lowest-assetID/highest-assetID price-pair, e.g. QORT/asset100
					// Note: HSQLDB uses BigDecimal's dividend.divide(divisor, RoundingMode.DOWN) too
					stmt.execute("UPDATE AssetOrders SET price = CAST(1 AS QortalAmount) / price "
							+ "WHERE ordered < timestamp(" + 0 /* was BlockChain.getInstance().getNewAssetPricingTimestamp() */ + ") "
							+ "AND have_asset_id < want_asset_id");
					// Revert time zone change above
					stmt.execute("SET TIME ZONE LOCAL");
					break;

				case 43:
					// More work on 'new' asset pricing - refunds due to price improvement
					stmt.execute("ALTER TABLE AssetTrades ADD initiator_saving QortalAmount NOT NULL DEFAULT 0");
					break;

				case 44:
					// Account flags
					stmt.execute("ALTER TABLE Accounts ADD COLUMN flags INT NOT NULL DEFAULT 0");
					// Corresponding transaction to set/clear flags
					stmt.execute("CREATE TABLE AccountFlagsTransactions (signature Signature, creator QortalPublicKey NOT NULL, target QortalAddress NOT NULL, and_mask INT NOT NULL, or_mask INT NOT NULL, xor_mask INT NOT NULL, "
							+ "previous_flags INT, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 45:
					// Enabling other accounts to forge
					// Transaction to allow one account to enable other account to forge
					stmt.execute("CREATE TABLE EnableForgingTransactions (signature Signature, creator QortalPublicKey NOT NULL, target QortalAddress NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Modification to accounts to record who enabled them to forge (useful for counting accounts and potentially orphaning)
					stmt.execute("ALTER TABLE Accounts ADD COLUMN forging_enabler QortalAddress");
					break;

				case 46:
					// Proxy forging
					// Transaction emitted by forger announcing they are forging on behalf of recipient
					stmt.execute("CREATE TABLE ProxyForgingTransactions (signature Signature, forger QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, proxy_public_key QortalPublicKey NOT NULL, share RewardSharePercent NOT NULL, "
							+ "previous_share RewardSharePercent, PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					// Table of current shares
					stmt.execute("CREATE TABLE ProxyForgers (forger QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, proxy_public_key QortalPublicKey NOT NULL, share RewardSharePercent NOT NULL, "
							+ "PRIMARY KEY (forger, recipient))");
					// Proxy-forged blocks will contain proxy public key, which will be used to look up block reward sharing, so create index for those lookups
					stmt.execute("CREATE INDEX ProxyForgersProxyPublicKeyIndex ON ProxyForgers (proxy_public_key)");
					break;

				case 47:
					// Stash of private keys used for generating blocks. These should be proxy keys!
					stmt.execute("CREATE TYPE QortalKeySeed AS VARBINARY(32)");
					stmt.execute("CREATE TABLE ForgingAccounts (forger_seed QortalKeySeed NOT NULL, PRIMARY KEY (forger_seed))");
					break;

				case 48:
					// Add index to TransactionParticipants to speed up queries
					stmt.execute("CREATE INDEX TransactionParticipantsAddressIndex on TransactionParticipants (participant)");
					break;

				case 49:
					// Additional peer information
					stmt.execute("ALTER TABLE Peers ADD COLUMN last_block_signature BlockSignature BEFORE last_misbehaved");
					stmt.execute("ALTER TABLE Peers ADD COLUMN last_block_timestamp TIMESTAMP WITH TIME ZONE BEFORE last_misbehaved");
					stmt.execute("ALTER TABLE Peers ADD COLUMN last_block_generator QortalPublicKey BEFORE last_misbehaved");
					break;

				case 50:
					// Cached block height in Transactions to save loads of JOINs
					stmt.execute("ALTER TABLE Transactions ADD COLUMN block_height INT");
					// Add height-based index
					stmt.execute("CREATE INDEX TransactionHeightIndex on Transactions (block_height)");
					break;

				case 51:
					// Transaction group-approval rework
					// Add index to GroupApprovalTransactions
					stmt.execute("CREATE INDEX GroupApprovalLatestIndex on GroupApprovalTransactions (pending_signature, admin)");
					// Transaction's approval status (Java enum) stored as tiny integer for efficiency
					stmt.execute("ALTER TABLE Transactions ADD COLUMN approval_status TINYINT NOT NULL");
					// For searching transactions based on approval status
					stmt.execute("CREATE INDEX TransactionApprovalStatusIndex on Transactions (approval_status, block_height)");
					// Height when/if transaction is finally approved
					stmt.execute("ALTER TABLE Transactions ADD COLUMN approval_height INT");
					// For searching transactions based on approval height
					stmt.execute("CREATE INDEX TransactionApprovalHeightIndex on Transactions (approval_height)");
					break;

				case 52:
					// Arbitrary transactions changes to allow storage of very small payloads locally
					stmt.execute("CREATE TYPE ArbitraryData AS VARBINARY(255)");
					stmt.execute("ALTER TABLE ArbitraryTransactions ADD COLUMN is_data_raw BOOLEAN NOT NULL");
					stmt.execute("ALTER TABLE ArbitraryTransactions ALTER COLUMN data_hash ArbitraryData");
					stmt.execute("ALTER TABLE ArbitraryTransactions ALTER COLUMN data_hash RENAME TO data");
					break;

				case 53:
					// Change what we store about peers (again)
					stmt.execute("ALTER TABLE Peers DROP COLUMN last_block_signature");
					stmt.execute("ALTER TABLE Peers DROP COLUMN last_block_timestamp");
					stmt.execute("ALTER TABLE Peers DROP COLUMN last_block_generator");
					stmt.execute("ALTER TABLE Peers DROP COLUMN last_height");
					stmt.execute("ALTER TABLE Peers ADD COLUMN added_when TIMESTAMP WITH TIME ZONE");
					stmt.execute("ALTER TABLE Peers ADD COLUMN added_by VARCHAR(255)");
					break;

				case 54:
					// Account 'level'
					stmt.execute("ALTER TABLE Accounts ADD COLUMN initial_level TINYINT NOT NULL DEFAULT 0");
					stmt.execute("ALTER TABLE Accounts ADD COLUMN level TINYINT NOT NULL DEFAULT 0");
					// Corresponding transaction to set level
					stmt.execute("CREATE TABLE AccountLevelTransactions (signature Signature, creator QortalPublicKey NOT NULL, target QortalAddress NOT NULL, level INT NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				case 55:
					// Storage of which level 1+ accounts were 'online' for a particular block. Used to distribute block rewards.
					stmt.execute("ALTER TABLE Blocks ADD COLUMN online_accounts VARBINARY(1048576)");
					stmt.execute("ALTER TABLE Blocks ADD COLUMN online_accounts_count INT NOT NULL DEFAULT 0");
					stmt.execute("ALTER TABLE Blocks ADD COLUMN online_accounts_timestamp TIMESTAMP WITH TIME ZONE");
					stmt.execute("ALTER TABLE Blocks ADD COLUMN online_accounts_signatures BLOB");
					break;

				case 56:
					// Modify assets to support "unspendable" flag so we can implement the representative legacy QORA asset.
					stmt.execute("ALTER TABLE Assets ADD COLUMN is_unspendable BOOLEAN NOT NULL DEFAULT FALSE BEFORE creation_group_id");
					stmt.execute("ALTER TABLE IssueAssetTransactions ADD COLUMN is_unspendable BOOLEAN NOT NULL DEFAULT FALSE BEFORE asset_id");
					break;

				case 57:
					// Modify accounts to keep track of how many blocks generated
					stmt.execute("ALTER TABLE Accounts ADD COLUMN blocks_generated INT NOT NULL DEFAULT 0");
					// Remove forging_enabler
					stmt.execute("ALTER TABLE Accounts DROP COLUMN forging_enabler");
					// Remove corresponding ENABLE_FORGING transaction
					stmt.execute("DROP TABLE EnableForgingTransactions");
					break;

				case 58:
					// Refactoring to unify/clarify block forging/generation/proxy-forging to simply "minting"
					// Account-related
					stmt.execute("ALTER TABLE Accounts ALTER COLUMN blocks_generated RENAME TO blocks_minted");
					// "proxy-forging" is now "reward-share"
					stmt.execute("ALTER TABLE ProxyForgers ALTER COLUMN proxy_public_key RENAME TO reward_share_public_key");
					stmt.execute("ALTER TABLE ProxyForgers ALTER COLUMN forger RENAME TO minter_public_key");
					stmt.execute("ALTER TABLE ProxyForgers ALTER COLUMN share RENAME TO share_percent");
					stmt.execute("ALTER TABLE ProxyForgers RENAME TO RewardShares");
					stmt.execute("CREATE INDEX RewardSharePublicKeyIndex ON RewardShares (reward_share_public_key)");
					stmt.execute("DROP INDEX ProxyForgersProxyPublicKeyIndex");
					// Reward-share transactions
					stmt.execute("ALTER TABLE ProxyForgingTransactions ALTER COLUMN forger RENAME TO minter_public_key");
					stmt.execute("ALTER TABLE ProxyForgingTransactions ALTER COLUMN proxy_public_key RENAME TO reward_share_public_key");
					stmt.execute("ALTER TABLE ProxyForgingTransactions ALTER COLUMN share RENAME TO share_percent");
					stmt.execute("ALTER TABLE ProxyForgingTransactions ALTER COLUMN previous_share RENAME TO previous_share_percent");
					stmt.execute("ALTER TABLE ProxyForgingTransactions RENAME TO RewardShareTransactions");
					// Accounts used by BlockMinter
					stmt.execute("ALTER TABLE ForgingAccounts ALTER COLUMN forger_seed RENAME TO minter_private_key");
					stmt.execute("ALTER TABLE ForgingAccounts RENAME TO MintingAccounts");
					// Blocks
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN generation RENAME TO minted");
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN generator RENAME TO minter");
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN generator_signature RENAME TO minter_signature");
					// Block-indexes
					stmt.execute("CREATE INDEX BlockMinterIndex ON Blocks (minter)");
					stmt.execute("DROP INDEX BlockGeneratorIndex");
					stmt.execute("CREATE INDEX BlockMintedHeightIndex ON Blocks (minted, height)");
					stmt.execute("DROP INDEX BlockGenerationHeightIndex");
					break;

				case 59:
					// Keeping track of QORT gained from holding legacy QORA
					stmt.execute("CREATE TABLE AccountQortFromQoraInfo (account QortalAddress, final_qort_from_qora QortalAmount, final_block_height INT, "
									+ "PRIMARY KEY (account), FOREIGN KEY (account) REFERENCES Accounts (account) ON DELETE CASCADE)");
					break;

				case 60:
					// Index for speeding up fetch legacy QORA holders for Block processing
					stmt.execute("CREATE INDEX AccountBalances_Asset_Balance_Index ON AccountBalances (asset_id, balance)");
					// Tracking height-history to account balances
					stmt.execute("CREATE TABLE HistoricAccountBalances (account QortalAddress, asset_id AssetID, height INT DEFAULT 1, balance QortalAmount NOT NULL, "
							+ "PRIMARY KEY (account, asset_id, height), FOREIGN KEY (account) REFERENCES Accounts (account) ON DELETE CASCADE)");
					// Create triggers on changes to AccountBalances rows to update historic
					stmt.execute("CREATE TRIGGER Historic_account_balance_insert_trigger AFTER INSERT ON AccountBalances REFERENCING NEW ROW AS new_row FOR EACH ROW "
							+ "INSERT INTO HistoricAccountBalances VALUES (new_row.account, new_row.asset_id, (SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks), new_row.balance) "
							+ "ON DUPLICATE KEY UPDATE balance = new_row.balance");
					stmt.execute("CREATE TRIGGER Historic_account_balance_update_trigger AFTER UPDATE ON AccountBalances REFERENCING NEW ROW AS new_row FOR EACH ROW "
							+ "INSERT INTO HistoricAccountBalances VALUES (new_row.account, new_row.asset_id, (SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks), new_row.balance) "
							+ "ON DUPLICATE KEY UPDATE balance = new_row.balance");
					break;

				case 61:
					// Rework triggers on AccountBalances as their block-height sub-queries are too slow
					stmt.execute("DROP TRIGGER Historic_account_balance_insert_trigger");
					stmt.execute("DROP TRIGGER Historic_account_balance_update_trigger");
					stmt.execute("CREATE TRIGGER Historic_account_balance_insert_trigger AFTER INSERT ON AccountBalances REFERENCING NEW ROW AS new_row FOR EACH ROW "
							+ "INSERT INTO HistoricAccountBalances VALUES "
							+ "(new_row.account, new_row.asset_id, (SELECT IFNULL(height, 0) + 1 FROM (SELECT height FROM Blocks ORDER BY height DESC LIMIT 1) AS BlockHeights), new_row.balance) "
							+ "ON DUPLICATE KEY UPDATE balance = new_row.balance");
					stmt.execute("CREATE TRIGGER Historic_account_balance_update_trigger AFTER UPDATE ON AccountBalances REFERENCING NEW ROW AS new_row FOR EACH ROW "
							+ "INSERT INTO HistoricAccountBalances VALUES "
							+ "(new_row.account, new_row.asset_id, (SELECT IFNULL(height, 0) + 1 FROM (SELECT height FROM Blocks ORDER BY height DESC LIMIT 1) AS BlockHeights), new_row.balance) "
							+ "ON DUPLICATE KEY UPDATE balance = new_row.balance");
					break;

				case 62:
					// Rework sub-queries that need to know next block height as currently they fail for genesis block and/or are still too slow
					// Table to hold next block height.
					stmt.execute("CREATE TABLE NextBlockHeight (height INT NOT NULL)");
					// Initial value - should work for empty DB or populated DB.
					stmt.execute("INSERT INTO NextBlockHeight VALUES (SELECT IFNULL(MAX(height), 0) + 1 FROM Blocks)");
					// We use triggers on Blocks to update a simple "next block height" table
					String blockUpdateSql = "UPDATE NextBlockHeight SET height = (SELECT height + 1 FROM Blocks ORDER BY height DESC LIMIT 1)";
					stmt.execute("CREATE TRIGGER Next_block_height_insert_trigger AFTER INSERT ON Blocks " + blockUpdateSql);
					stmt.execute("CREATE TRIGGER Next_block_height_update_trigger AFTER UPDATE ON Blocks " + blockUpdateSql);
					stmt.execute("CREATE TRIGGER Next_block_height_delete_trigger AFTER DELETE ON Blocks " + blockUpdateSql);
					// Now update previously slow/broken sub-queries
					stmt.execute("DROP TRIGGER Historic_account_balance_insert_trigger");
					stmt.execute("DROP TRIGGER Historic_account_balance_update_trigger");
					stmt.execute("CREATE TRIGGER Historic_account_balance_insert_trigger AFTER INSERT ON AccountBalances REFERENCING NEW ROW AS new_row FOR EACH ROW "
							+ "INSERT INTO HistoricAccountBalances VALUES "
							+ "(new_row.account, new_row.asset_id, (SELECT height from NextBlockHeight), new_row.balance) "
							+ "ON DUPLICATE KEY UPDATE balance = new_row.balance");
					stmt.execute("CREATE TRIGGER Historic_account_balance_update_trigger AFTER UPDATE ON AccountBalances REFERENCING NEW ROW AS new_row FOR EACH ROW "
							+ "INSERT INTO HistoricAccountBalances VALUES "
							+ "(new_row.account, new_row.asset_id, (SELECT height from NextBlockHeight), new_row.balance) "
							+ "ON DUPLICATE KEY UPDATE balance = new_row.balance");
					break;

				case 63:
					// Group invites should allow NULL expiry column
					stmt.execute("ALTER TABLE GroupInvites ALTER COLUMN expiry SET NULL");
					break;

				case 64:
					// TRANSFER_PRIVS transaction
					stmt.execute("CREATE TABLE TransferPrivsTransactions (signature Signature, sender QortalPublicKey NOT NULL, recipient QortalAddress NOT NULL, "
							+ "previous_sender_flags INT, previous_recipient_flags INT, "
							+ "previous_sender_blocks_minted_adjustment INT, previous_sender_blocks_minted INT, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");

					// Convert Account's "initial_level" to "blocks_minted_adjustment"
					stmt.execute("ALTER TABLE Accounts ADD blocks_minted_adjustment INT NOT NULL DEFAULT 0");

					List<Integer> blocksByLevel = BlockChain.getInstance().getBlocksNeededByLevel();
					for (int bbli = 0; bbli < blocksByLevel.size(); ++bbli)
						stmt.execute("UPDATE Accounts SET blocks_minted_adjustment = " + blocksByLevel.get(bbli) + " WHERE initial_level = " + (bbli + 1));

					stmt.execute("ALTER TABLE Accounts DROP initial_level");
					break;

				case 65:
					// Add INDEX to speed up very slow "DELETE FROM HistoricAccountBalances WHERE height >= ?"
					stmt.execute("CREATE INDEX IF NOT EXISTS HistoricAccountBalancesHeightIndex ON HistoricAccountBalances (height)");
					break;

				case 66:
					// Add CHECK constraint to account balances
					stmt.execute("ALTER TABLE AccountBalances ADD CONSTRAINT CheckBalanceNotNegative CHECK (balance >= 0)");
					break;

				case 67:
					// Provide external function to convert private keys to public keys
					stmt.execute("CREATE FUNCTION Ed25519_private_to_public_key (IN privateKey VARBINARY(32)) RETURNS VARBINARY(32) LANGUAGE JAVA DETERMINISTIC NO SQL EXTERNAL NAME 'CLASSPATH:org.qortal.repository.hsqldb.HSQLDBRepository.ed25519PrivateToPublicKey'");

					// Cache minting account public keys to save us recalculating them
					stmt.execute("ALTER TABLE MintingAccounts ADD minter_public_key QortalPublicKey");
					stmt.execute("UPDATE MintingAccounts SET minter_public_key = Ed25519_private_to_public_key(minter_private_key)");
					stmt.execute("ALTER TABLE MintingAccounts ALTER COLUMN minter_public_key SET NOT NULL");

					// Provide external function to convert public keys to addresses
					stmt.execute("CREATE FUNCTION Ed25519_public_key_to_address (IN privateKey VARBINARY(32)) RETURNS VARCHAR(36) LANGUAGE JAVA DETERMINISTIC NO SQL EXTERNAL NAME 'CLASSPATH:org.qortal.repository.hsqldb.HSQLDBRepository.ed25519PublicKeyToAddress'");

					// Cache reward-share minting account's address
					stmt.execute("ALTER TABLE RewardShares ADD minter QortalAddress BEFORE recipient");
					stmt.execute("UPDATE RewardShares SET minter = Ed25519_public_key_to_address(minter_public_key)");
					stmt.execute("ALTER TABLE RewardShares ALTER COLUMN minter SET NOT NULL");
					break;

				case 68:
					// Slow down log fsync() calls from every 500ms to reduce I/O load
					stmt.execute("SET FILES WRITE DELAY 5"); // only fsync() every 5 seconds
					break;

				case 69:
					// Get rid of historic account balances as they simply use up way too much space
					stmt.execute("DROP TRIGGER Historic_Account_Balance_Insert_Trigger");
					stmt.execute("DROP TRIGGER Historic_Account_Balance_Update_Trigger");
					stmt.execute("DROP TABLE HistoricAccountBalances");
					// Reclaim space
					stmt.execute("CHECKPOINT");
					stmt.execute("CHECKPOINT DEFRAG");
					break;

				case 70:
					// Reduce space used for storing online account in Blocks
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN online_accounts BLOB(1M)");
					stmt.execute("ALTER TABLE Blocks ALTER COLUMN online_accounts_signatures BLOB(1M)");
					// Reclaim space
					stmt.execute("CHECKPOINT");
					stmt.execute("CHECKPOINT DEFRAG");
					break;

				case 71:
					// Add flag to AT state data to indicate 'initial deployment state'
					stmt.execute("ALTER TABLE ATStates ADD COLUMN is_initial BOOLEAN NOT NULL DEFAULT TRUE");
					break;

				case 72:
					// For ATs, add hash of code bytes to allow searching for specific function ATs, e.g. cross-chain trading
					stmt.execute("ALTER TABLE ATs ADD COLUMN code_hash VARBINARY(32) NOT NULL BEFORE is_sleeping"); // Assuming something like SHA256
					break;

				case 73:
					// Chat transactions
					stmt.execute("CREATE TABLE ChatTransactions (signature Signature, sender QortalAddress NOT NULL, nonce INT NOT NULL, recipient QortalAddress, "
							+ "is_text BOOLEAN NOT NULL, is_encrypted BOOLEAN NOT NULL, data MessageData NOT NULL, "
							+ "PRIMARY KEY (signature), FOREIGN KEY (signature) REFERENCES Transactions (signature) ON DELETE CASCADE)");
					break;

				default:
					// nothing to do
					return false;
			}
		}

		// database was updated
		LOGGER.info(String.format("HSQLDB repository updated to version %d", databaseVersion + 1));
		return true;
	}

}
