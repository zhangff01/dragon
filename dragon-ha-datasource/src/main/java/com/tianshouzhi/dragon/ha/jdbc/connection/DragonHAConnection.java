package com.tianshouzhi.dragon.ha.jdbc.connection;

import com.tianshouzhi.dragon.common.jdbc.connection.DragonConnection;
import com.tianshouzhi.dragon.common.jdbc.sqltype.SqlTypeUtil;
import com.tianshouzhi.dragon.ha.hint.DragonHAHintUtil;
import com.tianshouzhi.dragon.ha.jdbc.datasource.DragonHADatasource;
import com.tianshouzhi.dragon.ha.jdbc.statement.DragonHAPrepareStatement;
import com.tianshouzhi.dragon.ha.jdbc.statement.DragonHAStatement;
import com.tianshouzhi.dragon.ha.util.DatasourceUtil;

import java.sql.*;
import java.util.Properties;

/**
 * Created by TIANSHOUZHI336 on 2016/12/3.
 */
public class DragonHAConnection extends DragonConnection implements Connection {

	/**
	 * 在没有使用读写分离的情况下，用户可能在一个Connection即执行读，也执行写。 在使用读写分离的时候，读需要使用读连接(ReadDBSelector)，写需要使用写连接(WriteDBSelector)。
	 * 为了简化使用，实现者只需要通过调用getRealConnection方法，既可以获取对应的连接
	 * <p>
	 * 在事务的情况下，总是需要保持对同一个连接的引用 对于不是事务的情况下，则可以每次重新通过DBSelector进行选择
	 */
	protected Connection realConnection;

	protected DragonHADatasource dragonHADatasource;

	private String realDSName;// 当前连接是从哪一个数据源中获取的

	public DragonHAConnection(String username, String password, DragonHADatasource dragonHADatasource)
	      throws SQLException {
		super(username, password);
		if (dragonHADatasource == null) {
			throw new SQLException("parameter 'dragonHADatasource' can't be null");
		}
		this.dragonHADatasource = dragonHADatasource;
	}

	// ==================================================创建Statement部分=========================================================
	@Override
	public DragonHAStatement createStatement() throws SQLException {
		checkClosed();
		DragonHAStatement dragonHAStatement = new DragonHAStatement(this);
		statementList.add(dragonHAStatement);
		return dragonHAStatement;
	}

	@Override
	public DragonHAStatement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		checkClosed();
		DragonHAStatement dragonHAStatement = new DragonHAStatement(resultSetType, resultSetConcurrency, this);
		statementList.add(dragonHAStatement);
		return dragonHAStatement;
	}

	@Override
	public DragonHAStatement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
	      throws SQLException {
		checkClosed();
		DragonHAStatement dragonHAStatement = new DragonHAStatement(resultSetType, resultSetConcurrency,
		      resultSetHoldability, this);
		statementList.add(dragonHAStatement);
		return dragonHAStatement;
	}

	@Override
	public DragonHAPrepareStatement prepareStatement(String sql) throws SQLException {
		checkClosed();
		DragonHAPrepareStatement dragonHAPrepareStatement = new DragonHAPrepareStatement(sql, this);
		statementList.add(dragonHAPrepareStatement);
		return dragonHAPrepareStatement;
	}

	@Override
	public DragonHAPrepareStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
	      throws SQLException {
		checkClosed();
		DragonHAPrepareStatement dragonHAPrepareStatement = new DragonHAPrepareStatement(sql, resultSetType,
		      resultSetConcurrency, this);
		statementList.add(dragonHAPrepareStatement);
		return dragonHAPrepareStatement;
	}

	@Override
	public DragonHAPrepareStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
	      int resultSetHoldability) throws SQLException {
		checkClosed();
		DragonHAPrepareStatement dragonHAPrepareStatement = new DragonHAPrepareStatement(sql, resultSetType,
		      resultSetConcurrency, resultSetHoldability, this);
		statementList.add(dragonHAPrepareStatement);
		return dragonHAPrepareStatement;
	}

	@Override
	public DragonHAPrepareStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		checkClosed();
		DragonHAPrepareStatement dragonHAPrepareStatement = new DragonHAPrepareStatement(sql, autoGeneratedKeys, this);
		statementList.add(dragonHAPrepareStatement);
		return dragonHAPrepareStatement;
	}

	@Override
	public DragonHAPrepareStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
		checkClosed();
		DragonHAPrepareStatement dragonHAPrepareStatement = new DragonHAPrepareStatement(sql, columnIndexes, this);
		statementList.add(dragonHAPrepareStatement);
		return dragonHAPrepareStatement;
	}

	@Override
	public DragonHAPrepareStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
		checkClosed();
		DragonHAPrepareStatement dragonHAPrepareStatement = new DragonHAPrepareStatement(sql, columnNames, this);
		statementList.add(dragonHAPrepareStatement);
		return dragonHAPrepareStatement;
	}

	/**
	 * 因为不知道存储过程中到底执行了什么，所以： 1、CallableStatement总是应该获取写连接 2、CallableStatement不重试，不需要建立一个类似的DragonHACallableStatement 3、Hint的问题
	 *
	 * @param sql
	 * @return
	 * @throws SQLException
	 */
	@Override
	public CallableStatement prepareCall(String sql) throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.prepareCall(sql);
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.prepareCall(sql, resultSetType, resultSetConcurrency);
	}

	@Override
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
	      int resultSetHoldability) throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
	}

	// ===============================================获取真实连接================================================================
	/**
	 * 获取真实连接，会根据以下情况自动切换真实连接 1 当前是只读连接，但是开启了事务 2 当前是只读连接，但是传入了一个写sql 3 其他情况，返回当前连接
	 *
	 * @param sql
	 * @return
	 * @throws SQLException
	 */
	public Connection getRealConnection(String sql, boolean useSqlTypeCache) throws SQLException {
		// 1、如果已经开启了事务 总是获取写连接
		if (!autoCommit) {
			return buildNewWriteConnectionIfNeed();
		}
		// 如果没有开启事务
		// 2、判断有没有ThreadLocal hint
		if (DragonHAHintUtil.isForceMaster()) {
			return buildNewWriteConnectionIfNeed();
		}

		// 3、sql中有hint
		if (DragonHAHintUtil.isForceMaster(sql)) {
			return buildNewWriteConnectionIfNeed();
		}

		// 4、没有hint且没有开启事务,如果是写sql
		if (!SqlTypeUtil.isQuery(sql, useSqlTypeCache)) {// retry for read connection
			return buildNewWriteConnectionIfNeed();
		}else{
			return buildNewReadConnectionIfNeed();
		}
	}

	private Connection buildNewReadConnectionIfNeed() throws SQLException {
		if (this.realConnection != null) {
			return realConnection;
		}
		this.realDSName = this.dragonHADatasource.getRouterManager().routeRead();
		this.realConnection = this.dragonHADatasource.getConnectionByRealDSName(realDSName);
		setConnectionParams(this.realConnection);
		return realConnection;
	}

	public Connection buildNewWriteConnectionIfNeed() throws SQLException {
		if (this.realConnection == null || this.realConnection.isReadOnly()) {
			DatasourceUtil.close(dragonHADatasource.getHADSName(), getRealDSName(),realConnection);
			this.realDSName = this.dragonHADatasource.getRouterManager().routeWrite();
			this.realConnection = this.dragonHADatasource.getConnectionByRealDSName(realDSName);
			setConnectionParams(this.realConnection);
			return this.realConnection;
		}
		return this.realConnection;
	}

	/**
	 * 针对关闭connection是否会自动关闭Statement和ResultSet的问题，以及Statement和ResultSet所占用资源是否会自动释放问题，JDBC处理规范或JDK规范中做了如下描述：
	 * 1、Connection关闭不一定会导致Statement关闭。 2、Statement关闭会导致ResultSet关闭； 3、如果直接关闭了Connection，Statemnt会有垃圾回收机制自动关闭
	 * 由于垃圾回收的线程级别是最低的，为了充分利用数据库资源，有必要显式关闭它们，最优经验是按照ResultSet，Statement，Connection的顺序执行close
	 * 为了避免由于java代码有问题导致内存泄露，需要在rs.close()和stmt.close()后面一定要加上rs = null和stmt = null；
	 *
	 * @throws SQLException
	 */
	@Override
	public void close() throws SQLException {
		if (isClosed()) {
			return;
		}
		if (realConnection != null) {
			realConnection.close();
			realConnection = null;
		}
		isClosed = true;
	}

	@Override
	public DatabaseMetaData getMetaData() throws SQLException {
		checkClosed();
		buildNewReadConnectionIfNeed();
		return realConnection.getMetaData();
	}

	@Override
	public void commit() throws SQLException {
		checkClosed();
		if (autoCommit) {
			throw new SQLException("This method should be used only when auto-commit mode has been disabled.");
		}
		if (realConnection != null) {
			realConnection.commit();
		}
	}

	/**
	 * 回滚到事务的最开始
	 */
	@Override
	public void rollback() throws SQLException {
		checkClosed();
		if (autoCommit) {
			throw new SQLException("This method should be used only when auto-commit mode has been disabled.");
		}
		if (realConnection != null) {
			realConnection.rollback();
		}
	}

	@Override
	public void rollback(Savepoint savepoint) throws SQLException {
		checkClosed();
		if (!autoCommit) {
			throw new SQLException("This method should be used only when auto-commit mode has been disabled.");
		}
		if (realConnection != null) {
			realConnection.rollback(savepoint);
		}
	}

	@Override
	public Savepoint setSavepoint() throws SQLException {
		checkClosed();
		this.autoCommit = false;
		buildNewWriteConnectionIfNeed();
		return realConnection.setSavepoint();
	}

	@Override
	public Savepoint setSavepoint(String name) throws SQLException {
		checkClosed();
		this.autoCommit = false;
		buildNewWriteConnectionIfNeed();
		return realConnection.setSavepoint(name);
	}

	/**
	 * JDBC规范，移除一个savepoint的时候，需要将这个savepoint以及之后的savepoint都移除， 当调用已经移除的savepoint的方法时，抛出SQLException异常
	 *
	 * @param savepoint
	 * @throws SQLException
	 */
	@Override
	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		checkClosed();
		if (realConnection != null)
			realConnection.releaseSavepoint(savepoint);
	}

	// ====================================================================

	@Override
	public SQLWarning getWarnings() throws SQLException {
		checkClosed();
		if (realConnection != null) {
			return realConnection.getWarnings();
		}
		return null;
	}

	@Override
	public void clearWarnings() throws SQLException {
		checkClosed();
		if (realConnection != null) {
			realConnection.clearWarnings();
		}
	}

	@Override
	public boolean isValid(int timeout) throws SQLException {
		if (timeout < 0) {
			throw new SQLException("the value supplied for timeout is less then 0");
		}
		if (realConnection == null) {
			return true;
		}
		return realConnection.isValid(timeout);
	}

	@Override
	public String getClientInfo(String name) throws SQLException {
		checkClosed();
		if (clientInfo != null) {
			return clientInfo.getProperty(name);
		}
		return null;
	}

	@Override
	public Properties getClientInfo() throws SQLException {
		checkClosed();
		if (realConnection != null) {
			return realConnection.getClientInfo();
		}
		return clientInfo;
	}

	// ======================调用create方法，默认创建一个写连接，因为只有插入或更新的的时候，可能才会用到create方法=========
	@Override
	public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.createArrayOf(typeName, elements);
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.createStruct(typeName, attributes);
	}

	@Override
	public Clob createClob() throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.createClob();
	}

	@Override
	public Blob createBlob() throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.createBlob();
	}

	@Override
	public NClob createNClob() throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.createNClob();
	}

	@Override
	public SQLXML createSQLXML() throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.createSQLXML();
	}

	public String getRealDSName() {
		return realDSName;
	}

	@Override
	public String nativeSQL(String sql) throws SQLException {
		checkClosed();
		buildNewWriteConnectionIfNeed();
		return realConnection.nativeSQL(sql);
	}

	@Override
	public String getCatalog() throws SQLException {
		buildNewReadConnectionIfNeed();
		return realConnection.getCatalog();
	}
}
