/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.rm.datasource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import io.seata.common.util.StringUtils;
import io.seata.rm.datasource.exec.ExecuteTemplate;

/**
 * The type Statement proxy.
 *
 * @param <T> the type parameter
 * @author sharajava
 */
public class StatementProxy<T extends Statement> extends AbstractStatementProxy<T> {

    /**
     * Instantiates a new Statement proxy.
     *
     * @param connectionWrapper the connection wrapper
     * @param targetStatement   the target statement
     * @param targetSQL         the target sql
     * @throws SQLException the sql exception
     */
    public StatementProxy(AbstractConnectionProxy connectionWrapper, T targetStatement, String targetSQL)
        throws SQLException {
        super(connectionWrapper, targetStatement, targetSQL);
    }

    /**
     * Instantiates a new Statement proxy.
     *
     * @param connectionWrapper the connection wrapper
     * @param targetStatement   the target statement
     * @throws SQLException the sql exception
     */
    public StatementProxy(AbstractConnectionProxy connectionWrapper, T targetStatement) throws SQLException {
        this(connectionWrapper, targetStatement, null);
    }

    @Override
    public ConnectionProxy getConnectionProxy() {
        return (ConnectionProxy) super.getConnectionProxy();
    }

    /**
     * seata对数据源到Statement都进行了代理，当执行SQL时，最终调用ExecuteTemplate.execute核心方法
     * @return
     * @throws SQLException
     */
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.executeQuery((String) args[0]), sql);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.executeUpdate((String) args[0]), sql);
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.execute((String) args[0]), sql);
    }


    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.executeUpdate((String) args[0],(int)args[1]), sql,autoGeneratedKeys);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.executeUpdate((String) args[0],(int [])args[1]), sql,columnIndexes);
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.executeUpdate((String) args[0],(String[])args[1]), sql,columnNames);
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.execute((String) args[0],(int)args[1]), sql,autoGeneratedKeys);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.execute((String) args[0],(int[])args[1]), sql,columnIndexes);
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        this.targetSQL = sql;
        return ExecuteTemplate.execute(this, (statement, args) -> statement.execute((String) args[0],(String[])args[1]), sql,columnNames);
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        if (StringUtils.isNotBlank(targetSQL)) {
            targetSQL += "; " + sql;
        } else {
            targetSQL = sql;
        }
        targetStatement.addBatch(sql);
    }

    @Override
    public int[] executeBatch() throws SQLException {
        return ExecuteTemplate.execute(this, (statement, args) -> statement.executeBatch());
    }
}
