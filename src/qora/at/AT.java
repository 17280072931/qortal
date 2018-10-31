package qora.at;

import java.math.BigDecimal;
import java.nio.ByteBuffer;

import org.ciyam.at.MachineState;

import data.at.ATData;
import data.at.ATStateData;
import data.transaction.DeployATTransactionData;
import qora.account.PublicKeyAccount;
import qora.crypto.Crypto;
import repository.DataException;
import repository.Repository;

public class AT {

	// Properties
	private Repository repository;
	private ATData atData;
	private ATStateData atStateData;

	// Constructors

	public AT(Repository repository, ATData atData, ATStateData atStateData) {
		this.repository = repository;
		this.atData = atData;
		this.atStateData = atStateData;
	}

	/** Deploying AT */
	public AT(Repository repository, DeployATTransactionData deployATTransactionData) throws DataException {
		this.repository = repository;

		String atAddress = deployATTransactionData.getATAddress();
		int height = this.repository.getBlockRepository().getBlockchainHeight() + 1;
		String creator = new PublicKeyAccount(repository, deployATTransactionData.getCreatorPublicKey()).getAddress();
		long creation = deployATTransactionData.getTimestamp();

		byte[] creationBytes = deployATTransactionData.getCreationBytes();
		short version = (short) (creationBytes[0] | (creationBytes[1] << 8)); // Little-endian

		if (version >= 2) {
			MachineState machineState = new MachineState(deployATTransactionData.getCreationBytes());

			this.atData = new ATData(atAddress, creator, creation, machineState.version, machineState.getCodeBytes(), machineState.getIsSleeping(),
					machineState.getSleepUntilHeight(), machineState.getIsFinished(), machineState.getHadFatalError(), machineState.getIsFrozen(),
					machineState.getFrozenBalance());

			byte[] stateData = machineState.toBytes();
			byte[] stateHash = Crypto.digest(stateData);

			this.atStateData = new ATStateData(atAddress, height, creation, stateData, stateHash, BigDecimal.ZERO.setScale(8));
		} else {
			// Legacy v1 AT in 'dead' state
			// Extract code bytes length
			ByteBuffer byteBuffer = ByteBuffer.wrap(deployATTransactionData.getCreationBytes());

			short numCodePages = byteBuffer.get(2 + 2);

			byteBuffer.position(6 * 2 + 8);
			int codeLen = 0;

			if (numCodePages * 256 < 257) {
				codeLen = (int) (byteBuffer.get() & 0xff);
			} else if (numCodePages * 256 < Short.MAX_VALUE + 1) {
				codeLen = byteBuffer.getShort() & 0xffff;
			} else if (numCodePages * 256 <= Integer.MAX_VALUE) {
				codeLen = byteBuffer.getInt();
			}

			// Extract code bytes
			byte[] codeBytes = new byte[codeLen];
			byteBuffer.get(codeBytes);

			this.atData = new ATData(atAddress, creator, creation, 1, codeBytes, false, null, true, false, false, (Long) null);

			this.atStateData = new ATStateData(atAddress, height, creation, null, null, BigDecimal.ZERO.setScale(8));
		}
	}

	// Processing

	public void deploy() throws DataException {
		this.repository.getATRepository().save(this.atData);
	}

	public void undeploy() throws DataException {
		// AT states deleted implicitly by repository
		this.repository.getATRepository().delete(this.atData.getATAddress());
	}
}
