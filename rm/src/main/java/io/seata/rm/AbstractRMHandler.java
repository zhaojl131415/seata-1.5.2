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
package io.seata.rm;

import io.seata.core.exception.AbstractExceptionHandler;
import io.seata.core.exception.TransactionException;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.ResourceManager;
import io.seata.core.protocol.AbstractMessage;
import io.seata.core.protocol.AbstractResultMessage;
import io.seata.core.protocol.transaction.*;
import io.seata.core.rpc.RpcContext;
import io.seata.core.rpc.TransactionMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抽象资源管理器事件处理器
 * The Abstract RM event handler
 *
 * @author sharajava
 */
public abstract class AbstractRMHandler extends AbstractExceptionHandler
    implements RMInboundHandler, TransactionMessageHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRMHandler.class);

    /**
     * 处理分支事务提交请求
     * @param request the request
     * @return
     */
    @Override
    public BranchCommitResponse handle(BranchCommitRequest request) {
        BranchCommitResponse response = new BranchCommitResponse();
        exceptionHandleTemplate(new AbstractCallback<BranchCommitRequest, BranchCommitResponse>() {
            @Override
            public void execute(BranchCommitRequest request, BranchCommitResponse response)
                throws TransactionException {
                /**
                 * 处理分支事务提交
                 */
                doBranchCommit(request, response);
            }
        }, request, response);
        return response;
    }

    /**
     * 处理分支事务回滚请求
     * @param request the request
     * @return
     */
    @Override
    public BranchRollbackResponse handle(BranchRollbackRequest request) {
        BranchRollbackResponse response = new BranchRollbackResponse();
        exceptionHandleTemplate(new AbstractCallback<BranchRollbackRequest, BranchRollbackResponse>() {
            @Override
            public void execute(BranchRollbackRequest request, BranchRollbackResponse response)
                throws TransactionException {
                /**
                 * 处理分支事务回滚
                 */
                doBranchRollback(request, response);
            }
        }, request, response);
        return response;
    }

    /**
     * delete undo log
     * @param request the request
     */
    @Override
    public void handle(UndoLogDeleteRequest request) {
        // https://github.com/seata/seata/issues/2226
    }

    /**
     * 执行分支事务提交
     * Do branch commit.
     *
     * @param request  the request
     * @param response the response
     * @throws TransactionException the transaction exception
     */
    protected void doBranchCommit(BranchCommitRequest request, BranchCommitResponse response)
        throws TransactionException {
        String xid = request.getXid();
        long branchId = request.getBranchId();
        String resourceId = request.getResourceId();
        String applicationData = request.getApplicationData();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Branch committing: " + xid + " " + branchId + " " + resourceId + " " + applicationData);
        }
        /**
         * 执行分支事务提交
         *
         * XA模式
         * @see io.seata.rm.datasource.xa.ResourceManagerXA#branchCommit(BranchType, String, long, String, String)
         * SAGA模式
         * @see io.seata.saga.rm.SagaResourceManager#branchCommit(BranchType, String, long, String, String)
         * TCC模式
         * @see io.seata.rm.tcc.TCCResourceManager#branchCommit(BranchType, String, long, String, String)
         */
        BranchStatus status = getResourceManager().branchCommit(request.getBranchType(), xid, branchId, resourceId,
            applicationData);
        response.setXid(xid);
        response.setBranchId(branchId);
        response.setBranchStatus(status);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Branch commit result: " + status);
        }

    }

    /**
     * 执行分支事务回滚
     * Do branch rollback.
     *
     * @param request  the request
     * @param response the response
     * @throws TransactionException the transaction exception
     */
    protected void doBranchRollback(BranchRollbackRequest request, BranchRollbackResponse response)
        throws TransactionException {
        String xid = request.getXid();
        long branchId = request.getBranchId();
        String resourceId = request.getResourceId();
        String applicationData = request.getApplicationData();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Branch Rollbacking: " + xid + " " + branchId + " " + resourceId);
        }
        /**
         * 执行分支事务回滚
         *
         * XA模式
         * @see io.seata.rm.datasource.xa.ResourceManagerXA#branchRollback(BranchType, String, long, String, String)
         * SAGA模式
         * @see io.seata.saga.rm.SagaResourceManager#branchRollback(BranchType, String, long, String, String)
         * TCC模式
         * @see io.seata.rm.tcc.TCCResourceManager#branchRollback(BranchType, String, long, String, String)
         */
        BranchStatus status = getResourceManager().branchRollback(request.getBranchType(), xid, branchId, resourceId,
            applicationData);
        response.setXid(xid);
        response.setBranchId(branchId);
        response.setBranchStatus(status);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Branch Rollbacked result: " + status);
        }
    }

    /**
     * get resource manager implement
     *
     * @return resource manager
     */
    protected abstract ResourceManager getResourceManager();

    /**
     * 处理咨询管理器请求
     * @param request received request message
     * @param context context of the RPC
     * @return
     */
    @Override
    public AbstractResultMessage onRequest(AbstractMessage request, RpcContext context) {
        if (!(request instanceof AbstractTransactionRequestToRM)) {
            throw new IllegalArgumentException();
        }
        AbstractTransactionRequestToRM transactionRequest = (AbstractTransactionRequestToRM)request;
        // 指定 事务请求的资源管理器入站消息处理器 为 当前资源管理器处理器
        transactionRequest.setRMInboundMessageHandler(this);
        /**
         * 根据不同的事务请求, 执行不同的处理: 策略模式
         * 分支事务提交请求
         * @see BranchCommitRequest#handle(RpcContext)
         * 分支事务回滚请求
         * @see BranchRollbackRequest#handle(RpcContext)
         *
         * ------------------undo log------------------
         * UndoLog删除请求
         * @see UndoLogDeleteRequest#handle(RpcContext)
         */
        return transactionRequest.handle(context);
    }

    @Override
    public void onResponse(AbstractResultMessage response, RpcContext context) {
        LOGGER.info("the rm client received response msg [{}] from tc server.", response.toString());
    }

    public abstract BranchType getBranchType();
}
