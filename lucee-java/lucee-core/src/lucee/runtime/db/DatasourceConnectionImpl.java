/**
 *
 * Copyright (c) 2014, the Railo Company Ltd. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either 
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public 
 * License along with this library.  If not, see <http://www.gnu.org/licenses/>.
 * 
 **/
package lucee.runtime.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import lucee.commons.lang.ExceptionUtil;
import lucee.commons.lang.StringUtil;
import lucee.runtime.config.Config;
import lucee.runtime.config.ConfigImpl;
import lucee.runtime.exp.PageException;
import lucee.runtime.op.Caster;
import lucee.runtime.spooler.Task;

/**
 * wrap for datasorce and connection from it
 */
public final class DatasourceConnectionImpl implements DatasourceConnection,Task {
    
    //private static final int MAX_PS = 100;
	private Connection connection;
    private DataSource datasource;
    private long time;
    private final long start;
	private String username;
	private String password;
	private int transactionIsolationLevel=-1;
	private int requestId=-1;
	private Boolean supportsGetGeneratedKeys;

    /**
     * @param connection
     * @param datasource
     * @param pass  
     * @param user 
     */
    public DatasourceConnectionImpl(Connection connection, DataSource datasource, String username, String password) {
        this.connection = connection;
        this.datasource = datasource;
        this.time=this.start=System.currentTimeMillis();
        this.username = username;
        this.password = password;
        
        if(username==null) {
        	this.username=datasource.getUsername();
        	this.password=datasource.getPassword();
        }
        if(this.password==null)this.password="";
		
    }
    
    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public DataSource getDatasource() {
        return datasource;
    }

    @Override
    public boolean isTimeout() {
        int timeout=datasource.getConnectionTimeout();
        if(timeout <= 0) return false;
        timeout*=60000;      
        return (time+timeout)<System.currentTimeMillis();
    }

    public boolean isLifecycleTimeout() {
        int timeout=datasource.getConnectionTimeout()*5;// fo3 the moment simply 5 times the idle timeout
        if(timeout <= 0) return false;
        timeout*=60000;      
        return (start+timeout)<System.currentTimeMillis();
    }

	public DatasourceConnection using() {
		time=System.currentTimeMillis();
		return this;
	}

	/**
	 * @return the password
	 */
	public String getPassword() {
		return password;
	}

	/**
	 * @return the username
	 */
	public String getUsername() {
		return username;
	}

	@Override
	public boolean equals(Object obj) {
		if(this==obj) return true;
		
		if(!(obj instanceof DatasourceConnectionImpl)) return false;
		return equals(this, (DatasourceConnection) obj);
		
		
		/*if(!(obj instanceof DatasourceConnectionImpl)) return false;
		DatasourceConnectionImpl other=(DatasourceConnectionImpl) obj;
		
		if(!datasource.equals(other.datasource)) return false;
		//print.out(username+".equals("+other.username+") && "+password+".equals("+other.password+")");
		return username.equals(other.username) && password.equals(other.password);*/
	}
	
	public static boolean equals(DatasourceConnection left,DatasourceConnection right) {
		
		if(!left.getDatasource().equals(right.getDatasource())) return false;
		return StringUtil.emptyIfNull(left.getUsername()).equals(StringUtil.emptyIfNull(right.getUsername())) 
			&& StringUtil.emptyIfNull(left.getPassword()).equals(StringUtil.emptyIfNull(right.getPassword()));
	}
	
	

	/**
	 * @return the transactionIsolationLevel
	 */
	public int getTransactionIsolationLevel() {
		return transactionIsolationLevel;
	}

	
	public int getRequestId() {
		return requestId;
	}
	public void setRequestId(int requestId) {
		this.requestId=requestId;
	}

	@Override
	public boolean supportsGetGeneratedKeys() {
		if(supportsGetGeneratedKeys==null){
			try {
				supportsGetGeneratedKeys=Caster.toBoolean(getConnection().getMetaData().supportsGetGeneratedKeys());
			} catch (Throwable t) {
            	ExceptionUtil.rethrowIfNecessary(t);
				return false;
			}
		}
		return supportsGetGeneratedKeys.booleanValue();
	}
	
	//private Map<String,PreparedStatement> preparedStatements=new HashMap<String, PreparedStatement>();
	
	@Override
	public PreparedStatement getPreparedStatement(SQL sql, boolean createGeneratedKeys,boolean allowCaching) throws SQLException {
		if(createGeneratedKeys)	return getConnection().prepareStatement(sql.getSQLString(),Statement.RETURN_GENERATED_KEYS);
		return getConnection().prepareStatement(sql.getSQLString());
	}
	
	
	/*public PreparedStatement getPreparedStatement(SQL sql, boolean createGeneratedKeys,boolean allowCaching) throws SQLException {
		// create key
		String strSQL=sql.getSQLString();
		String key=strSQL.trim()+":"+createGeneratedKeys;
		try {
			key = MD5.getDigestAsString(key);
		} catch (IOException e) {}
		PreparedStatement ps = allowCaching?preparedStatements.get(key):null;
		if(ps!=null) {
			if(DataSourceUtil.isClosed(ps,true)) 
				preparedStatements.remove(key);
			else return ps;
		}
		
		
		if(createGeneratedKeys)	ps= getConnection().prepareStatement(strSQL,Statement.RETURN_GENERATED_KEYS);
		else ps=getConnection().prepareStatement(strSQL);
		if(preparedStatements.size()>MAX_PS)
			closePreparedStatements((preparedStatements.size()-MAX_PS)+1);
		if(allowCaching)preparedStatements.put(key,ps);
		return ps;
	}*/
	
	

	@Override
	public PreparedStatement getPreparedStatement(SQL sql, int resultSetType,int resultSetConcurrency) throws SQLException {
		return getConnection().prepareStatement(sql.getSQLString(),resultSetType,resultSetConcurrency);
	}
	
	/*
	 
	public PreparedStatement getPreparedStatement(SQL sql, int resultSetType,int resultSetConcurrency) throws SQLException {
		boolean allowCaching=false;
		// create key
		String strSQL=sql.getSQLString();
		String key=strSQL.trim()+":"+resultSetType+":"+resultSetConcurrency;
		try {
			key = MD5.getDigestAsString(key);
		} catch (IOException e) {}
		PreparedStatement ps = allowCaching?preparedStatements.get(key):null;
		if(ps!=null) {
			if(DataSourceUtil.isClosed(ps,true)) 
				preparedStatements.remove(key);
			else return ps;
		}
		
		ps=getConnection().prepareStatement(strSQL,resultSetType,resultSetConcurrency);
		if(preparedStatements.size()>MAX_PS)
			closePreparedStatements((preparedStatements.size()-MAX_PS)+1);
		if(allowCaching)preparedStatements.put(key,ps);
		return ps;
	}
	 */
	

	@Override
	public void close() throws SQLException {
		//closePreparedStatements(-1);
		getConnection().close();
	}

	@Override
	public Object execute(Config config) throws PageException {
		((ConfigImpl)config).getDatasourceConnectionPool().releaseDatasourceConnection(this);
		return null;
	}
	
}