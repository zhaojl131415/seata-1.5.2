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
import io.seata.core.protocol.transaction.BranchCommitRequest;
import io.seata.core.protocol.transaction.BranchCommitResponse;
import io.seata.core.rpc.RemotingClient;
import io.seata.core.rpc.RpcContext;
import io.seata.core.rpc.TransactionMessageHandler;
import io.seata.core.rpc.processor.RemotingProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行事务协调者的全局提交命令: 消息类型: BranchCommitRequest
 * process TC global commit command.
 * <p>
 * process message type:
 * {@link BranchCommitRequest}
 *
 * @author zhangchenghui.dev@gmail.com
 * @since 1.3.0
 */
public class RmBranchCommitProcessor implements RemotingProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RmBranchCommitProcessor.class);

    private TransactionMessageHandler handler;

    private RemotingClient remotingClient;

    public RmBranchCommitProcessor(TransactionMessageHandler handler, RemotingClient remotingClient) {
        this.handler = handler;
        this.remotingClient = remotingClient;
    }

    /**
     * 分支事务接到事务协调者的提交命令, 处理提交
     * @param ctx        Channel handler context.
     * @param rpcMessage rpc message.
     * @throws Exception
     */
    @Override
    public void process(ChannelHandlerContext ctx, RpcMessage rpcMessage) throws Exception {
        String remoteAddress = NetUtil.toStringAddress(ctx.channel().remoteAddress());
        Object msg = rpcMessage.getBody();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("rm client handle branch commit process:" + msg);
        }
        // 处理分支事务提交
        handleBranchCommit(rpcMessage, remoteAddress, (BranchCommitRequest) msg);
    }

    private void handleBranchCommit(RpcMessage request, String serverAddress, BranchCommitRequest branchCommitRequest) {
        BranchCommitResponse resultMessage;
        /**
         * 调用资源管理器处理分支事务提交请求
         * @see io.seata.rm.AbstractRMHandler#onRequest(AbstractMessage, RpcContext)
         */
        resultMessage = (BranchCommitResponse) handler.onRequest(branchCommitRequest, null);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("branch commit result:" + resultMessage);
        }
        try {
            this.remotingClient.sendAsyncResponse(serverAddress, request, resultMessage);
        } catch (Throwable throwable) {
            LOGGER.error("branch commit error: {}", throwable.getMessage(), throwable);
        }
    }
}
