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
package io.seata.server.session;

import io.seata.core.exception.BranchTransactionException;
import io.seata.core.exception.GlobalTransactionException;
import io.seata.core.exception.TransactionException;
import io.seata.core.exception.TransactionExceptionCode;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.GlobalStatus;
import io.seata.core.model.LockStatus;
import io.seata.server.storage.db.store.DataBaseTransactionStoreManager;
import io.seata.server.storage.file.store.FileTransactionStoreManager;
import io.seata.server.storage.redis.store.RedisTransactionStoreManager;
import io.seata.server.store.SessionStorable;
import io.seata.server.store.TransactionStoreManager;
import io.seata.server.store.TransactionStoreManager.LogOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The type Abstract session manager.
 */
public abstract class AbstractSessionManager implements SessionManager, SessionLifecycleListener {

    /**
     * The constant LOGGER.
     */
    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractSessionManager.class);

    /**
     * 事务存储管理器: 根据配置文件中的持久化配置属性{@code store.mode}获取对应的实现
     * The Transaction store manager.
     */
    protected TransactionStoreManager transactionStoreManager;

    /**
     * The Name.
     */
    protected String name;

    /**
     * Instantiates a new Abstract session manager.
     */
    public AbstractSessionManager() {
    }

    /**
     * Instantiates a new Abstract session manager.
     *
     * @param name the name
     */
    public AbstractSessionManager(String name) {
        this.name = name;
    }

    /**
     * 新增全局事务会话
     * @param session the session
     * @throws TransactionException
     */
    @Override
    public void addGlobalSession(GlobalSession session) throws TransactionException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MANAGER[{}] SESSION[{}] {}", name, session, LogOperation.GLOBAL_ADD);
        }
        writeSession(LogOperation.GLOBAL_ADD, session);
    }

    /**
     * 修改全局事务会话
     * @param session the session
     * @param status  the status
     * @throws TransactionException
     */
    @Override
    public void updateGlobalSessionStatus(GlobalSession session, GlobalStatus status) throws TransactionException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MANAGER[{}] SESSION[{}] {}", name, session, LogOperation.GLOBAL_UPDATE);
        }
        if (GlobalStatus.Rollbacking == status) {
            session.getBranchSessions().forEach(i -> i.setLockStatus(LockStatus.Rollbacking));
        }
        // 修改全局事务会话的状态: 回滚中
        writeSession(LogOperation.GLOBAL_UPDATE, session);
    }

    /**
     * 移除全局事务会话
     * @param session the session
     * @throws TransactionException
     */
    @Override
    public void removeGlobalSession(GlobalSession session) throws TransactionException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MANAGER[{}] SESSION[{}] {}", name, session, LogOperation.GLOBAL_REMOVE);
        }
        writeSession(LogOperation.GLOBAL_REMOVE, session);
    }

    /**
     * 新增分支事务会话
     * @param session the global session
     * @param branchSession       the session
     * @throws TransactionException
     */
    @Override
    public void addBranchSession(GlobalSession session, BranchSession branchSession) throws TransactionException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MANAGER[{}] SESSION[{}] {}", name, branchSession, LogOperation.BRANCH_ADD);
        }
        writeSession(LogOperation.BRANCH_ADD, branchSession);
    }

    /**
     * 修改分支事务会话
     * @param branchSession the session
     * @param status  the status
     * @throws TransactionException
     */
    @Override
    public void updateBranchSessionStatus(BranchSession branchSession, BranchStatus status)
        throws TransactionException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MANAGER[{}] SESSION[{}] {}", name, branchSession, LogOperation.BRANCH_UPDATE);
        }
        writeSession(LogOperation.BRANCH_UPDATE, branchSession);
    }

    /**
     * 移除分支事务会话
     * @param globalSession the global session
     * @param branchSession       the session
     * @throws TransactionException
     */
    @Override
    public void removeBranchSession(GlobalSession globalSession, BranchSession branchSession)
        throws TransactionException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("MANAGER[{}] SESSION[{}] {}", name, branchSession, LogOperation.BRANCH_REMOVE);
        }
        writeSession(LogOperation.BRANCH_REMOVE, branchSession);
    }

    @Override
    public void onBegin(GlobalSession globalSession) throws TransactionException {
        // 新增全局事务会话
        addGlobalSession(globalSession);
    }

    @Override
    public void onStatusChange(GlobalSession globalSession, GlobalStatus status) throws TransactionException {
        // 修改全局事务会话
        updateGlobalSessionStatus(globalSession, status);
    }

    @Override
    public void onBranchStatusChange(GlobalSession globalSession, BranchSession branchSession, BranchStatus status)
        throws TransactionException {
        // 修改分支事务会话
        updateBranchSessionStatus(branchSession, status);
    }

    @Override
    public void onAddBranch(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
        // 新增分支事务会话
        addBranchSession(globalSession, branchSession);
    }

    @Override
    public void onRemoveBranch(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
        // 移除分支事务会话
        removeBranchSession(globalSession, branchSession);
    }

    @Override
    public void onClose(GlobalSession globalSession) throws TransactionException {
        globalSession.setActive(false);
    }

    @Override
    public void onSuccessEnd(GlobalSession globalSession) throws TransactionException {
        // 移除全局事务会话
        removeGlobalSession(globalSession);
    }

    @Override
    public void onFailEnd(GlobalSession globalSession) throws TransactionException {
        LOGGER.info("xid:{} fail end, transaction:{}",globalSession.getXid(),globalSession.toString());
    }

    private void writeSession(LogOperation logOperation, SessionStorable sessionStorable) throws TransactionException {
        /**
         * 事务存储管理器, 持久化写入session
         * 根据配置文件中的持久化配置属性{@code store.mode}获取对应的实现
         * file
         * @see FileTransactionStoreManager#writeSession(LogOperation, SessionStorable)
         * DB
         * @see DataBaseTransactionStoreManager#writeSession(LogOperation, SessionStorable)
         * redis
         * @see RedisTransactionStoreManager#writeSession(LogOperation, SessionStorable)
         */
        if (!transactionStoreManager.writeSession(logOperation, sessionStorable)) {
            if (LogOperation.GLOBAL_ADD.equals(logOperation)) {
                throw new GlobalTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Fail to store global session");
            } else if (LogOperation.GLOBAL_UPDATE.equals(logOperation)) {
                throw new GlobalTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Fail to update global session");
            } else if (LogOperation.GLOBAL_REMOVE.equals(logOperation)) {
                throw new GlobalTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Fail to remove global session");
            } else if (LogOperation.BRANCH_ADD.equals(logOperation)) {
                throw new BranchTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Fail to store branch session");
            } else if (LogOperation.BRANCH_UPDATE.equals(logOperation)) {
                throw new BranchTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Fail to update branch session");
            } else if (LogOperation.BRANCH_REMOVE.equals(logOperation)) {
                throw new BranchTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Fail to remove branch session");
            } else {
                throw new BranchTransactionException(TransactionExceptionCode.FailedWriteSession,
                    "Unknown LogOperation:" + logOperation.name());
            }
        }
    }

    @Override
    public void destroy() {
    }

    /**
     * Sets transaction store manager.
     *
     * @param transactionStoreManager the transaction store manager
     */
    public void setTransactionStoreManager(TransactionStoreManager transactionStoreManager) {
        this.transactionStoreManager = transactionStoreManager;
    }
}
