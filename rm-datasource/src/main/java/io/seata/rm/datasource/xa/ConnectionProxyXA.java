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

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.PooledConnection;
import javax.sql.XAConnection;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import io.seata.common.DefaultValues;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.context.RootContext;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.rm.BaseDataSourceResource;
import io.seata.rm.DefaultResourceManager;
import io.seata.sqlparser.util.JdbcConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.seata.common.ConfigurationKeys.XA_BRANCH_EXECUTION_TIMEOUT;

/**
 * Connection proxy for XA mode.
 *
 * @author sharajava
 */
public class ConnectionProxyXA extends AbstractConnectionProxyXA implements Holdable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionProxyXA.class);

    private static final int BRANCH_EXECUTION_TIMEOUT = ConfigurationFactory.getInstance().getInt(XA_BRANCH_EXECUTION_TIMEOUT,
            DefaultValues.DEFAULT_XA_BRANCH_EXECUTION_TIMEOUT);

    private volatile boolean currentAutoCommitStatus = true;

    private volatile XAXid xaBranchXid;

    private volatile boolean xaActive = false;

    private volatile boolean kept = false;

    private volatile boolean rollBacked = false;

    private volatile Long branchRegisterTime = null;

    private volatile Long prepareTime = null;

    private volatile Integer timeout = null;

    /**
     * Constructor of Connection Proxy for XA mode.
     *
     * @param originalConnection Normal Connection from the original DataSource.
     * @param xaConnection XA Connection based on physical connection of the normal Connection above.
     * @param resource The corresponding Resource(DataSource proxy) from which the connections was created.
     * @param xid Seata global transaction xid.
     */
    public ConnectionProxyXA(Connection originalConnection, XAConnection xaConnection, BaseDataSourceResource resource, String xid) {
        super(originalConnection, xaConnection, resource, xid);
    }

    public void init() {
        try {
            this.xaResource = xaConnection.getXAResource();
            this.currentAutoCommitStatus = this.originalConnection.getAutoCommit();
            if (!currentAutoCommitStatus) {
                throw new IllegalStateException("Connection[autocommit=false] as default is NOT supported");
            }
            Integer transactionTimeout = RootContext.getTimeout();
            if (transactionTimeout == null) {
                transactionTimeout = DefaultValues.DEFAULT_GLOBAL_TRANSACTION_TIMEOUT;
            }
            timeout = Math.max(BRANCH_EXECUTION_TIMEOUT, transactionTimeout);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

    }

    private void keepIfNecessary() {
        resource.hold(xaBranchXid.toString(), this);
    }

    private void releaseIfNecessary() {
        if (this.xaBranchXid != null) {
            String xaBranchXid = this.xaBranchXid.toString();
            if (isHeld()) {
                // 释放分支
                resource.release(xaBranchXid, this);
            }
        }
    }

    /**
     * XA模式 二阶段预提交
     * XA commit
     * @param xid global transaction xid
     * @param branchId transaction branch id
     * @param applicationData application data
     * @throws SQLException SQLException
     */
    public synchronized void xaCommit(String xid, long branchId, String applicationData) throws XAException {
        XAXid xaXid = XAXidBuilder.build(xid, branchId);
        xaResource.commit(xaXid, false);
        releaseIfNecessary();
    }

    /**
     * XA模式 回滚
     * XA rollback
     * @param xid global transaction xid
     * @param branchId transaction branch id
     * @param applicationData application data
     * @throws SQLException  SQLException
     */
    public synchronized void xaRollback(String xid, long branchId, String applicationData) throws XAException {
        XAXid xaXid = XAXidBuilder.build(xid, branchId);
        xaRollback(xaXid);
    }

    /**
     * XA模式 回滚
     * XA rollback
     * @param xaXid xaXid
     * @throws XAException XAException
     */
    public void xaRollback(XAXid xaXid) throws XAException {
        xaResource.rollback(xaXid);
        releaseIfNecessary();
    }

    /**
     * XA模式 取消自动提交: 开启XA模式分支事务
     * @param autoCommit <code>true</code> to enable auto-commit mode;
     *         <code>false</code> to disable it
     * @throws SQLException
     */
    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (currentAutoCommitStatus == autoCommit) {
            return;
        }
        if (autoCommit) {
            // According to JDBC spec:
            // If this method is called during a transaction and the
            // auto-commit mode is changed, the transaction is committed.
            if (xaActive) {
                commit();
            }
        } else {
            if (xaActive) {
                throw new SQLException("should NEVER happen: setAutoCommit from true to false while xa branch is active");
            }
            // Start a XA branch 开启一个XA模式的分支事务
            long branchId;
            try {
                // 1. register branch to TC then get the branch message
                branchRegisterTime = System.currentTimeMillis();
                /**
                 * 将分支事务注册到TC
                 * @see DefaultResourceManager#branchRegister(BranchType, String, String, String, String, String)
                 */
                branchId = DefaultResourceManager.get().branchRegister(BranchType.XA, resource.getResourceId(), null, xid, null,
                        null);
            } catch (TransactionException te) {
                cleanXABranchContext();
                throw new SQLException("failed to register xa branch " + xid + " since " + te.getCode() + ":" + te.getMessage(), te);
            }
            // 2. build XA-Xid with xid and branchId 构建XA模式分支事务id
            this.xaBranchXid = XAXidBuilder.build(xid, branchId);
            // Keep the Connection if necessary
            keepIfNecessary();
            try {
                // 开启XA模式分支事务
                start();
            } catch (XAException e) {
                cleanXABranchContext();
                throw new SQLException("failed to start xa branch " + xid + " since " + e.getMessage(), e);
            }
            // 4. XA is active
            this.xaActive = true;

        }

        currentAutoCommitStatus = autoCommit;
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        return currentAutoCommitStatus;
    }

    /**
     * XA模式 一阶段预提交
     * @throws SQLException
     */
    @Override
    public synchronized void commit() throws SQLException {
        if (currentAutoCommitStatus) {
            // Ignore the committing on an autocommit session.
            return;
        }
        if (!xaActive || this.xaBranchXid == null) {
            throw new SQLException("should NOT commit on an inactive session", SQLSTATE_XA_NOT_END);
        }
        try {
            // XA模式分支事务结束
            end(XAResource.TMSUCCESS);
            long now = System.currentTimeMillis();
            checkTimeout(now);
            setPrepareTime(now);
            // 一阶段的预提交
            xaResource.prepare(xaBranchXid);
        } catch (XAException xe) {
            try {
                // Branch Report to TC: Failed 如果捕获到异常, 分支事务上报提交失败给TC
                DefaultResourceManager.get().branchReport(BranchType.XA, xid, xaBranchXid.getBranchId(),
                    BranchStatus.PhaseOne_Failed, null);
            } catch (TransactionException te) {
                LOGGER.warn("Failed to report XA branch commit-failure on " + xid + "-" + xaBranchXid.getBranchId()
                    + " since " + te.getCode() + ":" + te.getMessage() + " and XAException:" + xe.getMessage());

            }
            throw new SQLException(
                "Failed to end(TMSUCCESS)/prepare xa branch on " + xid + "-" + xaBranchXid.getBranchId() + " since " + xe
                    .getMessage(), xe);
        } finally {
            cleanXABranchContext();
        }
    }

    /**
     * XA模式 回滚
     * @throws SQLException
     */
    @Override
    public void rollback() throws SQLException {
        if (currentAutoCommitStatus) {
            // Ignore the committing on an autocommit session.
            return;
        }
        if (!xaActive || this.xaBranchXid == null) {
            throw new SQLException("should NOT rollback on an inactive session");
        }
        try {
            if (!rollBacked) {
                // XA End: Fail XA模式分支事务结束: 失败
                xaResource.end(this.xaBranchXid, XAResource.TMFAIL);
                // XA模式分支事务回滚
                xaRollback(xaBranchXid);
            }
            // Branch Report to TC 分支事务上报失败给TC
            DefaultResourceManager.get().branchReport(BranchType.XA, xid, xaBranchXid.getBranchId(),
                BranchStatus.PhaseOne_Failed, null);
            LOGGER.info(xaBranchXid + " was rollbacked");
        } catch (XAException xe) {
            throw new SQLException("Failed to end(TMFAIL) xa branch on " + xid + "-" + xaBranchXid.getBranchId()
                + " since " + xe.getMessage(), xe);
        } catch (TransactionException te) {
            // log and ignore the report failure
            LOGGER.warn("Failed to report XA branch rollback on " + xid + "-" + xaBranchXid.getBranchId() + " since "
                + te.getCode() + ":" + te.getMessage());
        } finally {
            cleanXABranchContext();
        }
    }

    private synchronized void start() throws XAException, SQLException {
        // 3. XA Start
        /**
         * XA模式启动本地事务: 调用支持XA模式的数据源源码实现(MySql)
         * @see com.mysql.jdbc.jdbc2.optional.MysqlXAConnection#start(Xid, int)
         */
        xaResource.start(this.xaBranchXid, XAResource.TMNOFLAGS);
        try {
            termination();
        } catch (SQLException e) {
            // the framework layer does not actively call ROLLBACK when setAutoCommit throws an SQL exception
            xaResource.end(this.xaBranchXid, XAResource.TMFAIL);
            xaRollback(xaBranchXid);
            throw  e;
        }
    }

    private synchronized void end(int flags) throws XAException, SQLException {
        termination();
        // XA End: Success
        xaResource.end(xaBranchXid, flags);
    }

    private void cleanXABranchContext() {
        branchRegisterTime = null;
        prepareTime = null;
        timeout = null;
        xaActive = false;
        if (!isHeld()) {
            xaBranchXid = null;
        }
    }

    private void checkTimeout(Long now) throws XAException {
        if (now - branchRegisterTime > timeout) {
            xaRollback(xaBranchXid);
            throw new XAException("XA branch timeout error");
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        rollBacked = false;
        if (isHeld() && shouldBeHeld()) {
            // if kept by a keeper, just hold the connection.
            return;
        }
        cleanXABranchContext();
        originalConnection.close();
    }

    protected synchronized void closeForce() throws SQLException {
        Connection physicalConn = getWrappedConnection();
        if (physicalConn instanceof PooledConnection) {
            physicalConn = ((PooledConnection) physicalConn).getConnection();
        }
        // Force close the physical connection
        physicalConn.close();
        rollBacked = false;
        cleanXABranchContext();
        originalConnection.close();
        releaseIfNecessary();
    }

    @Override
    public void setHeld(boolean kept) {
        this.kept = kept;
    }

    @Override
    public boolean isHeld() {
        return kept;
    }

    @Override
    public boolean shouldBeHeld() {
        return JdbcConstants.MYSQL.equals(resource.getDbType()) || JdbcConstants.MARIADB.equals(resource.getDbType())
               || StringUtils.isBlank(resource.getDbType());
    }

    public Long getPrepareTime() {
        return prepareTime;
    }

    private void setPrepareTime(Long prepareTime) {
        this.prepareTime = prepareTime;
    }

    private void termination() throws SQLException {
        termination(this.xaBranchXid.toString());
    }

    private void termination(String xaBranchXid) throws SQLException {
        // if it is not empty, the resource will hang and need to be terminated early
        BranchStatus branchStatus = BaseDataSourceResource.getBranchStatus(xaBranchXid);
        if (branchStatus != null) {
            releaseIfNecessary();
            throw new SQLException("failed xa branch " + xid
                    + " the global transaction has finish, branch status: " + branchStatus.getCode());
        }
    }

}
