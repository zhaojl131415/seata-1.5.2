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
package io.seata.core.protocol.transaction;

import io.seata.core.protocol.MessageType;
import io.seata.core.rpc.RpcContext;

/**
 * 分式事务提交请求
 * The type Branch commit request.
 *
 * @author sharajava
 */
public class BranchCommitRequest extends AbstractBranchEndRequest {

    @Override
    public short getTypeCode() {
        return MessageType.TYPE_BRANCH_COMMIT;
    }

    /**
     * 处理分式事务提交请求
     * @param rpcContext the rpc context
     * @return
     */
    @Override
    public AbstractTransactionResponse handle(RpcContext rpcContext) {
        /**
         * 处理分支事务提交请求
         * @see io.seata.rm.DefaultRMHandler#handle(BranchCommitRequest)
         */
        return handler.handle(this);
    }


}
