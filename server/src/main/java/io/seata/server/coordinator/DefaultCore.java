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
package io.seata.server.coordinator;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.seata.common.DefaultValues;
import io.seata.common.exception.NotSupportYetException;
import io.seata.common.loader.EnhancedServiceLoader;
import io.seata.common.util.CollectionUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.context.RootContext;
import io.seata.core.exception.TransactionException;
import io.seata.core.logger.StackTraceLogger;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.core.rpc.RemotingServer;
import io.seata.server.metrics.MetricsPublisher;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHelper;
import io.seata.server.session.SessionHolder;
import io.seata.server.transaction.at.ATCore;
import io.seata.server.transaction.saga.SagaCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static io.seata.core.constants.ConfigurationKeys.XAER_NOTA_RETRY_TIMEOUT;
import static io.seata.server.session.BranchSessionHandler.CONTINUE;

/**
 * The type Default core.
 *
 * @author sharajava
 */
public class DefaultCore implements Core {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCore.class);

    private static final int RETRY_XAER_NOTA_TIMEOUT = ConfigurationFactory.getInstance().getInt(XAER_NOTA_RETRY_TIMEOUT,
            DefaultValues.DEFAULT_XAER_NOTA_RETRY_TIMEOUT);

    private static Map<BranchType, AbstractCore> coreMap = new ConcurrentHashMap<>();

    /**
     * get the Default core.
     *
     * @param remotingServer the remoting server
     */
    public DefaultCore(RemotingServer remotingServer) {
        List<AbstractCore> allCore = EnhancedServiceLoader.loadAll(AbstractCore.class,
            new Class[] {RemotingServer.class}, new Object[] {remotingServer});
        if (CollectionUtils.isNotEmpty(allCore)) {
            for (AbstractCore core : allCore) {
                coreMap.put(core.getHandleBranchType(), core);
            }
        }
    }

    /**
     * get core
     *
     * @param branchType the branchType
     * @return the core
     */
    public AbstractCore getCore(BranchType branchType) {
        AbstractCore core = coreMap.get(branchType);
        if (core == null) {
            throw new NotSupportYetException("unsupported type:" + branchType.name());
        }
        return core;
    }

    /**
     * only for mock
     *
     * @param branchType the branchType
     * @param core       the core
     */
    public void mockCore(BranchType branchType, AbstractCore core) {
        coreMap.put(branchType, core);
    }

    @Override
    public Long branchRegister(BranchType branchType, String resourceId, String clientId, String xid,
                               String applicationData, String lockKeys) throws TransactionException {
        /**
         * @see AbstractCore#branchRegister(BranchType, String, String, String, String, String)
         */
        return getCore(branchType).branchRegister(branchType, resourceId, clientId, xid,
            applicationData, lockKeys);
    }

    @Override
    public void branchReport(BranchType branchType, String xid, long branchId, BranchStatus status,
                             String applicationData) throws TransactionException {
        /**
         * @see AbstractCore#branchReport(BranchType, String, long, BranchStatus, String)
         */
        getCore(branchType).branchReport(branchType, xid, branchId, status, applicationData);
    }

    @Override
    public boolean lockQuery(BranchType branchType, String resourceId, String xid, String lockKeys)
        throws TransactionException {
        /**
         * AT模式
         * @see ATCore#lockQuery(BranchType, String, String, String)
         */
        return getCore(branchType).lockQuery(branchType, resourceId, xid, lockKeys);
    }

    @Override
    public BranchStatus branchCommit(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
        return getCore(branchSession.getBranchType()).branchCommit(globalSession, branchSession);
    }

    @Override
    public BranchStatus branchRollback(GlobalSession globalSession, BranchSession branchSession) throws TransactionException {
        /**
         * 给分支事务发送回滚请求
         * @see AbstractCore#branchRollback(GlobalSession, BranchSession)
         */
        return getCore(branchSession.getBranchType()).branchRollback(globalSession, branchSession);
    }

    /**
     * 开启全局事务, 并返回事务id: XID
     * @param applicationId           ID of the application who begins this transaction.
     * @param transactionServiceGroup ID of the transaction service group.
     * @param name                    Give a name to the global transaction.
     * @param timeout                 Timeout of the global transaction.
     * @return
     * @throws TransactionException
     */
    @Override
    public String begin(String applicationId, String transactionServiceGroup, String name, int timeout)
        throws TransactionException {
        // 创建全局会话,
        GlobalSession session = GlobalSession.createGlobalSession(applicationId, transactionServiceGroup, name,
            timeout);
        MDC.put(RootContext.MDC_KEY_XID, session.getXid());
        // 添加会话生命周期监听器
        session.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        /**
         * 新增全局事务会话: 持久化
         * 根据配置文件中的store.mode 找到对应的实现, 进行持久化
         */
        session.begin();

        // transaction start event 发布事务开启事件
        MetricsPublisher.postSessionDoingEvent(session, false);

        return session.getXid();
    }

    @Override
    public GlobalStatus commit(String xid) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid);
        if (globalSession == null) {
            // 全局事务会话信息为空, 可能已经提交了
            return GlobalStatus.Finished;
        }
        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        // just lock changeStatus

        boolean shouldCommit = SessionHolder.lockAndExecute(globalSession, () -> {
            // 如果全局事务的状态为开启
            if (globalSession.getStatus() == GlobalStatus.Begin) {
                // Highlight: Firstly, close the session, then no more branch can be registered. 亮点: 首先关闭会话，然后不再可以注册分支。
                globalSession.closeAndClean();
                if (globalSession.canBeCommittedAsync()) {
                    // 如果能一步提交, 则执行异步提交
                    globalSession.asyncCommit();
                    MetricsPublisher.postSessionDoneEvent(globalSession, GlobalStatus.Committed, false, false);
                    return false;
                } else {
                    // 否则将全局事务的状态设置为提交中
                    globalSession.changeGlobalStatus(GlobalStatus.Committing);
                    return true;
                }
            }
            return false;
        });

        if (shouldCommit) {
            // 执行全局事务提交
            boolean success = doGlobalCommit(globalSession, false);
            //If successful and all remaining branches can be committed asynchronously, do async commit.
            if (success && globalSession.hasBranch() && globalSession.canBeCommittedAsync()) {
                globalSession.asyncCommit();
                return GlobalStatus.Committed;
            } else {
                return globalSession.getStatus();
            }
        } else {
            return globalSession.getStatus() == GlobalStatus.AsyncCommitting ? GlobalStatus.Committed : globalSession.getStatus();
        }
    }

    @Override
    public boolean doGlobalCommit(GlobalSession globalSession, boolean retrying) throws TransactionException {
        boolean success = true;
        // start committing event
        MetricsPublisher.postSessionDoingEvent(globalSession, retrying);

        if (globalSession.isSaga()) {
            /**
             * SAGA模式 全局事务提交
             * @see SagaCore#doGlobalCommit(GlobalSession, boolean)
             */
            success = getCore(BranchType.SAGA).doGlobalCommit(globalSession, retrying);
        } else {
            // 遍历所有分支事务, 通知各分支事务执行提交
            Boolean result = SessionHelper.forEach(globalSession.getSortedBranches(), branchSession -> {
                // if not retrying, skip the canBeCommittedAsync branches
                if (!retrying && branchSession.canBeCommittedAsync()) {
                    return CONTINUE;
                }

                BranchStatus currentStatus = branchSession.getStatus();
                if (currentStatus == BranchStatus.PhaseOne_Failed) {
                    SessionHelper.removeBranch(globalSession, branchSession, !retrying);
                    return CONTINUE;
                }
                try {
                    /**
                     * 通过netty发送异步分支事务提交请求
                     * @see AbstractCore#branchCommit(GlobalSession, BranchSession)
                     */
                    BranchStatus branchStatus = getCore(branchSession.getBranchType()).branchCommit(globalSession, branchSession);
                    if (isXaerNotaTimeout(globalSession,branchStatus)) {
                        LOGGER.info("Commit branch XAER_NOTA retry timeout, xid = {} branchId = {}", globalSession.getXid(), branchSession.getBranchId());
                        branchStatus = BranchStatus.PhaseTwo_Committed;
                    }
                    switch (branchStatus) {
                        case PhaseTwo_Committed:
                            // 两阶段提交: 移除分支事务
                            SessionHelper.removeBranch(globalSession, branchSession, !retrying);
                            return CONTINUE;
                        case PhaseTwo_CommitFailed_Unretryable:
                            //not at branch
                            SessionHelper.endCommitFailed(globalSession, retrying);
                            LOGGER.error("Committing global transaction[{}] finally failed, caused by branch transaction[{}] commit failed.", globalSession.getXid(), branchSession.getBranchId());
                            return false;

                        default:
                            if (!retrying) {
                                globalSession.queueToRetryCommit();
                                return false;
                            }
                            if (globalSession.canBeCommittedAsync()) {
                                LOGGER.error("Committing branch transaction[{}], status:{} and will retry later",
                                    branchSession.getBranchId(), branchStatus);
                                return CONTINUE;
                            } else {
                                LOGGER.error(
                                    "Committing global transaction[{}] failed, caused by branch transaction[{}] commit failed, will retry later.", globalSession.getXid(), branchSession.getBranchId());
                                return false;
                            }
                    }
                } catch (Exception ex) {
                    StackTraceLogger.error(LOGGER, ex, "Committing branch transaction exception: {}",
                        new String[] {branchSession.toString()});
                    if (!retrying) {
                        globalSession.queueToRetryCommit();
                        throw new TransactionException(ex);
                    }
                }
                return CONTINUE;
            });
            // Return if the result is not null
            if (result != null) {
                return result;
            }
            //If has branch and not all remaining branches can be committed asynchronously,
            //do print log and return false
            if (globalSession.hasBranch() && !globalSession.canBeCommittedAsync()) {
                LOGGER.info("Committing global transaction is NOT done, xid = {}.", globalSession.getXid());
                return false;
            }
            if (!retrying) {
                //contains not AT branch
                globalSession.setStatus(GlobalStatus.Committed);
            }
        }
        // if it succeeds and there is no branch, retrying=true is the asynchronous state when retrying. EndCommitted is
        // executed to improve concurrency performance, and the global transaction ends..
        if (success && globalSession.getBranchSessions().isEmpty()) {
            /**
             * 全局事务结束
             */
            SessionHelper.endCommitted(globalSession, retrying);
            LOGGER.info("Committing global transaction is successfully done, xid = {}.", globalSession.getXid());
        }
        return success;
    }

    /**
     * 全局事务回滚
     * @param xid XID of the global transaction
     * @return
     * @throws TransactionException
     */
    @Override
    public GlobalStatus rollback(String xid) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid);
        if (globalSession == null) {
            return GlobalStatus.Finished;
        }
        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        // just lock changeStatus
        boolean shouldRollBack = SessionHolder.lockAndExecute(globalSession, () -> {
            globalSession.close(); // Highlight: Firstly, close the session, then no more branch can be registered.
            if (globalSession.getStatus() == GlobalStatus.Begin) {
                // 更新全局事务会话的状态: 回滚中
                globalSession.changeGlobalStatus(GlobalStatus.Rollbacking);
                return true;
            }
            return false;
        });
        if (!shouldRollBack) {
            return globalSession.getStatus();
        }
        // 执行全局事务回滚
        boolean rollbackSuccess = doGlobalRollback(globalSession, false);
        return rollbackSuccess ? GlobalStatus.Rollbacked : globalSession.getStatus();
    }

    @Override
    public boolean doGlobalRollback(GlobalSession globalSession, boolean retrying) throws TransactionException {
        boolean success = true;
        // start rollback event
        MetricsPublisher.postSessionDoingEvent(globalSession, retrying);

        if (globalSession.isSaga()) {
            /**
             * SAGA模式 全局事务回滚
             * @see SagaCore#doGlobalRollback(GlobalSession, boolean)
             */
            success = getCore(BranchType.SAGA).doGlobalRollback(globalSession, retrying);
        } else {
            // 遍历所有分支事务, 通知各分支事务执行回滚
            Boolean result = SessionHelper.forEach(globalSession.getReverseSortedBranches(), branchSession -> {
                BranchStatus currentBranchStatus = branchSession.getStatus();
                // 一阶段失败
                if (currentBranchStatus == BranchStatus.PhaseOne_Failed) {
                    // 移除分支事务
                    SessionHelper.removeBranch(globalSession, branchSession, !retrying);
                    return CONTINUE;
                }
                try {
                    /**
                     * 遍历所有分支事务, 通过netty发送分支事务回滚请求
                     */
                    BranchStatus branchStatus = branchRollback(globalSession, branchSession);
                    if (isXaerNotaTimeout(globalSession, branchStatus)) {
                        LOGGER.info("Rollback branch XAER_NOTA retry timeout, xid = {} branchId = {}", globalSession.getXid(), branchSession.getBranchId());
                        branchStatus = BranchStatus.PhaseTwo_Rollbacked;
                    }
                    switch (branchStatus) {
                        case PhaseTwo_Rollbacked:
                            SessionHelper.removeBranch(globalSession, branchSession, !retrying);
                            LOGGER.info("Rollback branch transaction successfully, xid = {} branchId = {}", globalSession.getXid(), branchSession.getBranchId());
                            return CONTINUE;
                        case PhaseTwo_RollbackFailed_Unretryable:
                            SessionHelper.endRollbackFailed(globalSession, retrying);
                            LOGGER.info("Rollback branch transaction fail and stop retry, xid = {} branchId = {}", globalSession.getXid(), branchSession.getBranchId());
                            return false;
                        default:
                            LOGGER.info("Rollback branch transaction fail and will retry, xid = {} branchId = {}", globalSession.getXid(), branchSession.getBranchId());
                            if (!retrying) {
                                globalSession.queueToRetryRollback();
                            }
                            return false;
                    }
                } catch (Exception ex) {
                    StackTraceLogger.error(LOGGER, ex,
                        "Rollback branch transaction exception, xid = {} branchId = {} exception = {}",
                        new String[] {globalSession.getXid(), String.valueOf(branchSession.getBranchId()), ex.getMessage()});
                    if (!retrying) {
                        globalSession.queueToRetryRollback();
                    }
                    throw new TransactionException(ex);
                }
            });
            // Return if the result is not null
            if (result != null) {
                return result;
            }
        }

        // In db mode, lock and branch data residual problems may occur.
        // Therefore, execution needs to be delayed here and cannot be executed synchronously.
        if (success) {
            // 全局事务回滚结束
            SessionHelper.endRollbacked(globalSession, retrying);
            LOGGER.info("Rollback global transaction successfully, xid = {}.", globalSession.getXid());
        }
        return success;
    }

    @Override
    public GlobalStatus getStatus(String xid) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid, false);
        if (globalSession == null) {
            return GlobalStatus.Finished;
        } else {
            return globalSession.getStatus();
        }
    }

    @Override
    public GlobalStatus globalReport(String xid, GlobalStatus globalStatus) throws TransactionException {
        GlobalSession globalSession = SessionHolder.findGlobalSession(xid);
        if (globalSession == null) {
            return globalStatus;
        }
        globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
        doGlobalReport(globalSession, xid, globalStatus);
        return globalSession.getStatus();
    }

    @Override
    public void doGlobalReport(GlobalSession globalSession, String xid, GlobalStatus globalStatus) throws TransactionException {
        if (globalSession.isSaga()) {
            getCore(BranchType.SAGA).doGlobalReport(globalSession, xid, globalStatus);
        }
    }

    private boolean isXaerNotaTimeout(GlobalSession globalSession, BranchStatus branchStatus) {
        if (BranchStatus.PhaseTwo_CommitFailed_XAER_NOTA_Retryable.equals(branchStatus) ||
                BranchStatus.PhaseTwo_RollbackFailed_XAER_NOTA_Retryable.equals(branchStatus)) {
            return System.currentTimeMillis() > globalSession.getBeginTime() + globalSession.getTimeout() +
                    Math.max(RETRY_XAER_NOTA_TIMEOUT, globalSession.getTimeout());
        } else {
            return false;
        }
    }

}
