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
package io.seata.rm.datasource.xa;

import io.seata.rm.datasource.exec.StatementCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * The type Execute template.
 *
 * @author sharajava
 */
public class ExecuteTemplateXA {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecuteTemplateXA.class);

    /**
     * XA模式 执行sql
     * @param connectionProxyXA
     * @param statementCallback
     * @param targetStatement
     * @param args
     * @return
     * @param <T>
     * @param <S>
     * @throws SQLException
     */
    public static <T, S extends Statement> T execute(AbstractConnectionProxyXA connectionProxyXA,
                                                     StatementCallback<T, S> statementCallback,
                                                     S targetStatement,
                                                     Object... args) throws SQLException {
        boolean autoCommitStatus = connectionProxyXA.getAutoCommit();
        if (autoCommitStatus) {
            // XA Start
            /**
             * 将自动提交改为false: 手动提交, 开启XA模式分支事务
             * @see ConnectionProxyXA#setAutoCommit(boolean)
             */
            connectionProxyXA.setAutoCommit(false);
        }
        try {
            T res = null;
            try {
                // execute SQL 回调执行业务sql
                res = statementCallback.execute(targetStatement, args);

            } catch (Throwable ex) {
                if (autoCommitStatus) {
                    // XA End & Rollback 回滚
                    try {
                        connectionProxyXA.rollback();
                    } catch (SQLException sqle) {
                        // log and ignore the rollback failure.
                        LOGGER.warn(
                            "Failed to rollback xa branch of " + connectionProxyXA.xid +
                                "(caused by SQL execution failure(" + ex.getMessage() + ") since " + sqle.getMessage(),
                            sqle);
                    }
                }

                if (ex instanceof SQLException) {
                    throw ex;
                } else {
                    throw new SQLException(ex);
                }

            }
            if (autoCommitStatus) {
                try {
                    // XA End & Prepare
                    /**
                     * XA模式 一阶段预提交
                     * @see ConnectionProxyXA#commit()
                     */
                    connectionProxyXA.commit();
                } catch (Throwable ex) {
                    LOGGER.warn(
                        "Failed to commit xa branch of " + connectionProxyXA.xid + ") since " + ex.getMessage(),
                        ex);
                    // XA End & Rollback
                    if (!(ex instanceof SQLException) || !AbstractConnectionProxyXA.SQLSTATE_XA_NOT_END.equalsIgnoreCase(((SQLException) ex).getSQLState())) {
                        try {
                            /**
                             * 回滚
                             * @see ConnectionProxyXA#rollback()
                             */
                            connectionProxyXA.rollback();
                        } catch (SQLException sqle) {
                            // log and ignore the rollback failure.
                            LOGGER.warn(
                                "Failed to rollback xa branch of " + connectionProxyXA.xid +
                                    "(caused by commit failure(" + ex.getMessage() + ") since " + sqle.getMessage(),
                                sqle);
                        }
                    }

                    if (ex instanceof SQLException) {
                        throw ex;
                    } else {
                        throw new SQLException(ex);
                    }

                }
            }
            return res;
        } finally {
            if (autoCommitStatus) {
                connectionProxyXA.setAutoCommit(true);
            }

        }
    }
}
