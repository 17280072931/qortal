package repository.hsqldb.transaction;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

import data.transaction.VoteOnPollTransactionData;
import data.transaction.TransactionData;
import repository.DataException;
import repository.hsqldb.HSQLDBRepository;
import repository.hsqldb.HSQLDBSaver;

public class HSQLDBVoteOnPollTransactionRepository extends HSQLDBTransactionRepository {

	public HSQLDBVoteOnPollTransactionRepository(HSQLDBRepository repository) {
		this.repository = repository;
	}

	TransactionData fromBase(byte[] signature, byte[] reference, byte[] creatorPublicKey, long timestamp, BigDecimal fee) throws DataException {
		try {
			ResultSet rs = this.repository
					.checkedExecute("SELECT poll_name, option_index, previous_option_index FROM VoteOnPollTransactions WHERE signature = ?", signature);
			if (rs == null)
				return null;

			String pollName = rs.getString(1);
			int optionIndex = rs.getInt(2);
			Integer previousOptionIndex = rs.getInt(3);

			return new VoteOnPollTransactionData(creatorPublicKey, pollName, optionIndex, previousOptionIndex, fee, timestamp, reference, signature);
		} catch (SQLException e) {
			throw new DataException("Unable to fetch vote on poll transaction from repository", e);
		}
	}

	@Override
	public void save(TransactionData transactionData) throws DataException {
		VoteOnPollTransactionData voteOnPollTransactionData = (VoteOnPollTransactionData) transactionData;

		HSQLDBSaver saveHelper = new HSQLDBSaver("VoteOnPollTransactions");

		saveHelper.bind("signature", voteOnPollTransactionData.getSignature()).bind("poll_name", voteOnPollTransactionData.getPollName())
				.bind("voter", voteOnPollTransactionData.getVoterPublicKey()).bind("option_index", voteOnPollTransactionData.getOptionIndex())
				.bind("previous_option_index", voteOnPollTransactionData.getPreviousOptionIndex());

		try {
			saveHelper.execute(this.repository);
		} catch (SQLException e) {
			throw new DataException("Unable to save vote on poll transaction into repository", e);
		}
	}

}
