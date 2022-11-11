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


/**
 * 事务协调者处理全局事务抽象类
 * The type Abstract transaction request to tc.
 *
 * @author sharajava
 */
public abstract class AbstractTransactionRequestToTC extends AbstractTransactionRequest {

    /**
     * 事务协调者入栈处理器
     * The Handler.
     */
    protected TCInboundHandler handler;

    /**
     * 指定事务协调者入栈处理器
     * Sets tc inbound handler.
     *
     * @param handler the handler
     */
    public void setTCInboundHandler(TCInboundHandler handler) {
        this.handler = handler;
    }
}