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
package io.seata.core.rpc.processor.client;

import io.netty.channel.ChannelHandlerContext;
import io.seata.common.util.NetUtil;
import io.seata.core.protocol.AbstractMessage;
import io.seata.core.protocol.RpcMessage;
import io.seata.core.protocol.transaction.BranchRollbackRequest;
import io.seata.core.protocol.transaction.BranchRollbackResponse;
import io.seata.core.rpc.RemotingClient;
import io.seata.core.rpc.RpcContext;
import io.seata.core.rpc.TransactionMessageHandler;
import io.seata.core.rpc.processor.RemotingProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行事务协调者的全局回滚命令: 消息类型: BranchRollbackRequest
 * process TC do global rollback command.
 * <p>
 * process message type:
 * {@link BranchRollbackRequest}
 *
 * @author zhangchenghui.dev@gmail.com
 * @since 1.3.0
 */
public class RmBranchRollbackProcessor implements RemotingProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RmBranchRollbackProcessor.class);

    private TransactionMessageHandler handler;

    private RemotingClient remotingClient;

    public RmBranchRollbackProcessor(TransactionMessageHandler handler, RemotingClient remotingClient) {
        this.handler = handler;
        this.remotingClient = remotingClient;
    }

    /**
     * 分支事务接到事务协调者的回滚命令, 处理回滚
     * @param ctx        Channel handler context.
     * @param rpcMessage rpc message.
     * @throws Exception
     */
    @Override
    public void process(ChannelHandlerContext ctx, RpcMessage rpcMessage) throws Exception {
        String remoteAddress = NetUtil.toStringAddress(ctx.channel().remoteAddress());
        Object msg = rpcMessage.getBody();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("rm handle branch rollback process:" + msg);
        }
        // 处理分支事务回滚
        handleBranchRollback(rpcMessage, remoteAddress, (BranchRollbackRequest) msg);
    }

    private void handleBranchRollback(RpcMessage request, String serverAddress, BranchRollbackRequest branchRollbackRequest) {
        BranchRollbackResponse resultMessage;
        /**
         * 调用资源管理器处理分支事务回滚请求
         * @see io.seata.rm.AbstractRMHandler#onRequest(AbstractMessage, RpcContext)
         */
        resultMessage = (BranchRollbackResponse) handler.onRequest(branchRollbackRequest, null);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("branch rollback result:" + resultMessage);
        }
        try {
            this.remotingClient.sendAsyncResponse(serverAddress, request, resultMessage);
        } catch (Throwable throwable) {
            LOGGER.error("send response error: {}", throwable.getMessage(), throwable);
        }
    }
}
