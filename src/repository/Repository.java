package repository;

public interface Repository {

	public AccountRepository getAccountRepository();

	public AssetRepository getAssetRepository();

	public BlockRepository getBlockRepository();

	public TransactionRepository getTransactionRepository();

	public void saveChanges() throws DataException;

	public void discardChanges() throws DataException;

	public void close() throws DataException;

	public void rebuild() throws DataException;

}
