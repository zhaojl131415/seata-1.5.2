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
package io.seata.tm.api.transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author guoyao
 */
public final class TransactionHookManager {

    private TransactionHookManager() {

    }

    /**
     * 线程本地缓存: 用于缓存当前线程下所有的事务hook集合
     */
    private static final ThreadLocal<List<TransactionHook>> LOCAL_HOOKS = new ThreadLocal<>();

    /**
     * 获取当前线程中所有的事务Hook
     * get the current hooks
     *
     * @return TransactionHook list
     * @throws IllegalStateException IllegalStateException
     */
    public static List<TransactionHook> getHooks() throws IllegalStateException {
        List<TransactionHook> hooks = LOCAL_HOOKS.get();

        if (hooks == null || hooks.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(hooks);
    }

    /**
     * 添加一个新的hook
     * add new hook
     *
     * @param transactionHook transactionHook
     */
    public static void registerHook(TransactionHook transactionHook) {
        if (transactionHook == null) {
            throw new NullPointerException("transactionHook must not be null");
        }
        List<TransactionHook> transactionHooks = LOCAL_HOOKS.get();
        if (transactionHooks == null) {
            LOCAL_HOOKS.set(new ArrayList<>());
        }
        // 将事务hook添加到本地线程缓存中
        LOCAL_HOOKS.get().add(transactionHook);
    }

    /**
     * clear hooks
     */
    public static void clear() {
        LOCAL_HOOKS.remove();
    }
}
