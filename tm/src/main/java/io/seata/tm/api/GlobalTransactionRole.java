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

/**
 * 当前线程在全局事务中的角色
 * Role of current thread involve in a global transaction.
 *
 * @author sharajava
 */
public enum GlobalTransactionRole {

    /**
     * The Launcher. 事务发起者
     */
    // The one begins the current global transaction.
    Launcher,

    /**
     * The Participant. 事务参与者
     */
    // The one just joins into a existing global transaction.
    Participant
}
