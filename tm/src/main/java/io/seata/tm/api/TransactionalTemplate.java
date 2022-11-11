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
package io.seata.tm.api;

import java.util.List;

import io.seata.common.exception.ShouldNeverHappenException;
import io.seata.core.context.GlobalLockConfigHolder;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.GlobalLockConfig;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.api.transaction.Propagation;
import io.seata.tm.api.transaction.SuspendedResourcesHolder;
import io.seata.tm.api.transaction.TransactionHook;
import io.seata.tm.api.transaction.TransactionHookManager;
import io.seata.tm.api.transaction.TransactionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Template of executing business logic with a global transaction.
 *
 * @author sharajava
 */
public class TransactionalTemplate {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalTemplate.class);


    /**
     * Execute object.
     *
     * @param business the business
     * @return the object
     * @throws TransactionalExecutor.ExecutionException the execution exception
     */
    public Object execute(TransactionalExecutor business) throws Throwable {
        // 1. Get transactionInfo 获取当前业务的事务信息
        TransactionInfo txInfo = business.getTransactionInfo();
        if (txInfo == null) {
            throw new ShouldNeverHappenException("transactionInfo does not exist");
        }
        // 1.1 Get current transaction, if not null, the tx role is 'GlobalTransactionRole.Participant'.
        // 获取当前事务, 如果不存在, 则以 事务参与者 的角色实例化一个新事务
        GlobalTransaction tx = GlobalTransactionContext.getCurrent();

        // 1.2 Handle the transaction propagation. 处理事务传播机制
        Propagation propagation = txInfo.getPropagation();
        SuspendedResourcesHolder suspendedResourcesHolder = null;
        try {
            // 根据事务传播机制, 处理事务
            switch (propagation) {
                case NOT_SUPPORTED:
                    // 不支持事务: 如果事务存在，挂起事务
                    // If transaction is existing, suspend it.
                    if (existingTransaction(tx)) {
                        // 挂起事务
                        suspendedResourcesHolder = tx.suspend();
                    }
                    // Execute without transaction and return. 在没有事务的情况下执行业务方法并返回。
                    return business.execute();
                case REQUIRES_NEW:
                    // 如果事务存在，则将其挂起，然后开启一个新的事务。
                    // If transaction is existing, suspend it, and then begin new transaction.
                    if (existingTransaction(tx)) {
                        // 挂起事务
                        suspendedResourcesHolder = tx.suspend();
                        // 开启一个新的事务
                        tx = GlobalTransactionContext.createNew();
                    }
                    // Continue and execute with new transaction 继续并使用新事务执行业务方法
                    break;
                case SUPPORTS:
                    // 支持事务: 有事务就在事务中执行, 没事务就直接执行
                    // If transaction is not existing, execute without transaction. 如果事务不存在，则在没有事务的情况下执行
                    if (notExistingTransaction(tx)) {
                        // 执行业务方法
                        return business.execute();
                    }
                    // Continue and execute with new transaction 继续并使用新事务执行业务方法
                    break;
                case REQUIRED:
                    // If current transaction is existing, execute with current transaction, 如果当前事务存在，则使用当前事务执行，
                    // else continue and execute with new transaction. 否则继续并使用新事务执行。
                    break;
                case NEVER:
                    // If transaction is existing, throw exception. 如果事务存在, 则抛出异常
                    if (existingTransaction(tx)) {
                        throw new TransactionException(
                            String.format("Existing transaction found for transaction marked with propagation 'never', xid = %s"
                                    , tx.getXid()));
                    } else {
                        // Execute without transaction and return. 在没有事务的情况下执行并返回
                        return business.execute();
                    }
                case MANDATORY:
                    // If transaction is not existing, throw exception. 如果事务不存在, 抛出异常
                    if (notExistingTransaction(tx)) {
                        throw new TransactionException("No existing transaction found for transaction marked with propagation 'mandatory'");
                    }
                    // Continue and execute with current transaction.
                    break;
                default:
                    throw new TransactionException("Not Supported Propagation:" + propagation);
            }

            // 1.3 If null, create new transaction with role 'GlobalTransactionRole.Launcher'. 如果事务不存在, 则以事务发起者的角色创建一个新事务
            if (tx == null) {
                tx = GlobalTransactionContext.createNew();
            }

            // set current tx config to holder
            GlobalLockConfig previousConfig = replaceGlobalLockConfig(txInfo);

            try {
                // 2. If the tx role is 'GlobalTransactionRole.Launcher', send the request of beginTransaction to TC,
                //    else do nothing. Of course, the hooks will still be triggered.
                // 如果事务角色是 事务发起者，则将开启事务的请求发送给TC，否则什么也不做。当然，钩子仍然会被触发。
                /**
                 * 开启全局事务: 通过netty发送异步消息通知事务协调者开启全局事务
                 */
                beginTransaction(txInfo, tx);

                Object rs;
                try {
                    // Do Your Business
                    /**
                     * 执行业务方法: 被注解{@link io.seata.spring.annotation.GlobalTransactional}标记的方法
                     * 当业务方法中进行数据库操作时，seata通过数据库代理执行
                     * 解析SQL - 关闭自动提交 - 查询前镜像 - 执行业务SQL - 查询后镜像 - 根据前后镜像构建undo_log - 提交写入undo_log - 手动提交业务SQL
                     */
                    rs = business.execute();
                } catch (Throwable ex) {
                    // 3. The needed business exception to rollback. 业务异常需要回滚
                    /**
                     * 回滚全局事务: 通过netty发送异步消息通知事务协调者回滚全局事务
                     */
                    completeTransactionAfterThrowing(txInfo, tx, ex);
                    throw ex;
                }

                // 4. everything is fine, commit. 提交全局事务
                /**
                 * 提交全局事务: 通过netty发送异步消息通知事务协调者提交全局事务
                 */
                commitTransaction(tx);

                return rs;
            } finally {
                //5. clear
                resumeGlobalLockConfig(previousConfig);
                // 事务完成后, 触发钩子方法
                triggerAfterCompletion();
                cleanUp();
            }
        } finally {
            // If the transaction is suspended, resume it.
            if (suspendedResourcesHolder != null) {
                tx.resume(suspendedResourcesHolder);
            }
        }
    }

    private boolean existingTransaction(GlobalTransaction tx) {
        return tx != null;
    }

    private boolean notExistingTransaction(GlobalTransaction tx) {
        return tx == null;
    }

    private GlobalLockConfig replaceGlobalLockConfig(TransactionInfo info) {
        GlobalLockConfig myConfig = new GlobalLockConfig();
        myConfig.setLockRetryInterval(info.getLockRetryInterval());
        myConfig.setLockRetryTimes(info.getLockRetryTimes());
        return GlobalLockConfigHolder.setAndReturnPrevious(myConfig);
    }

    private void resumeGlobalLockConfig(GlobalLockConfig config) {
        if (config != null) {
            GlobalLockConfigHolder.setAndReturnPrevious(config);
        } else {
            GlobalLockConfigHolder.remove();
        }
    }

    private void completeTransactionAfterThrowing(TransactionInfo txInfo, GlobalTransaction tx, Throwable originalException) throws TransactionalExecutor.ExecutionException {
        //roll back
        if (txInfo != null && txInfo.rollbackOn(originalException)) {
            try {
                /**
                 * 回滚全局事务: 通过netty发送异步消息通知事务协调者回滚全局事务
                 */
                rollbackTransaction(tx, originalException);
            } catch (TransactionException txe) {
                // Failed to rollback
                throw new TransactionalExecutor.ExecutionException(tx, txe,
                        TransactionalExecutor.Code.RollbackFailure, originalException);
            }
        } else {
            // not roll back on this exception, so commit
            commitTransaction(tx);
        }
    }

    private void commitTransaction(GlobalTransaction tx) throws TransactionalExecutor.ExecutionException {
        try {
            // 触发事务提交前的hook方法
            triggerBeforeCommit();
            /**
             * 提交全局事务: 通过netty发送异步消息通知事务协调者提交全局事务
             * @see DefaultGlobalTransaction#commit()
             */
            tx.commit();
            // 触发事务提交前的hook方法
            triggerAfterCommit();
        } catch (TransactionException txe) {
            // 4.1 Failed to commit
            throw new TransactionalExecutor.ExecutionException(tx, txe,
                TransactionalExecutor.Code.CommitFailure);
        }
    }

    private void rollbackTransaction(GlobalTransaction tx, Throwable originalException) throws TransactionException, TransactionalExecutor.ExecutionException {
        // 触发事务回滚前的hook方法
        triggerBeforeRollback();
        /**
         * 回滚全局事务: 通过netty发送异步消息通知事务协调者回滚全局事务
         * @see DefaultGlobalTransaction#rollback()
         */
        tx.rollback();
        // 触发事务回滚前的hook方法
        triggerAfterRollback();
        // 3.1 Successfully rolled back
        throw new TransactionalExecutor.ExecutionException(tx, GlobalStatus.RollbackRetrying.equals(tx.getLocalStatus())
            ? TransactionalExecutor.Code.RollbackRetrying : TransactionalExecutor.Code.RollbackDone, originalException);
    }

    /**
     * 开启事务, 并执行事务开启前后的hook方法
     * @param txInfo
     * @param tx
     * @throws TransactionalExecutor.ExecutionException
     */
    private void beginTransaction(TransactionInfo txInfo, GlobalTransaction tx) throws TransactionalExecutor.ExecutionException {
        try {
            // 触发事务开启前的hook方法
            triggerBeforeBegin();
            /**
             * 开启全局事务: 通过netty发送异步消息通知事务协调者开启全局事务
             * 生成和绑定全局事务id
             * @see DefaultGlobalTransaction#begin(int, String)
             */
            tx.begin(txInfo.getTimeOut(), txInfo.getName());
            // 触发事务开启后的hook方法
            triggerAfterBegin();
        } catch (TransactionException txe) {
            throw new TransactionalExecutor.ExecutionException(tx, txe,
                TransactionalExecutor.Code.BeginFailure);

        }
    }

    private void triggerBeforeBegin() {
        // 遍历当前线程所有的hook, 调用其事务开启前方法: beforeBegin()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.beforeBegin();
            } catch (Exception e) {
                LOGGER.error("Failed execute beforeBegin in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterBegin() {
        // 遍历当前线程所有的hook, 调用其事务开启后方法: afterBegin()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterBegin();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterBegin in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerBeforeRollback() {
        // 遍历当前线程所有的hook, 调用其事务回滚前方法: beforeRollback()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.beforeRollback();
            } catch (Exception e) {
                LOGGER.error("Failed execute beforeRollback in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterRollback() {
        // 遍历当前线程所有的hook, 调用其事务回滚后方法: afterRollback()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterRollback();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterRollback in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerBeforeCommit() {
        // 遍历当前线程所有的hook, 调用其事务提交前方法: beforeCommit()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.beforeCommit();
            } catch (Exception e) {
                LOGGER.error("Failed execute beforeCommit in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterCommit() {
        // 遍历当前线程所有的hook, 调用其事务提交后方法: afterCommit()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterCommit();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterCommit in hook {}", e.getMessage(), e);
            }
        }
    }

    private void triggerAfterCompletion() {
        // 遍历当前线程所有的hook, 调用其事务完成后方法: afterCompletion()
        for (TransactionHook hook : getCurrentHooks()) {
            try {
                hook.afterCompletion();
            } catch (Exception e) {
                LOGGER.error("Failed execute afterCompletion in hook {}", e.getMessage(), e);
            }
        }
    }

    private void cleanUp() {
        TransactionHookManager.clear();
    }

    private List<TransactionHook> getCurrentHooks() {
        return TransactionHookManager.getHooks();
    }
}
