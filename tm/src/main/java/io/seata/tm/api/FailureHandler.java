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
 * 全局事务失败时回调接口
 * 扩展点: 默认实现只是打印了日志, 也可以自定义实现: 在实现类中重写方法, 对接邮件/钉钉等, 失败时调用发送给对应人员
 * Callback on failure.
 *
 * @author slievrly
 */
public interface FailureHandler {

    /**
     * 全局事务开启失败回调
     * On begin failure.
     *
     * @param tx    the tx
     * @param cause the cause
     */
    void onBeginFailure(GlobalTransaction tx, Throwable cause);

    /**
     * 全局事务回提交失败回调
     * On commit failure.
     *
     * @param tx    the tx
     * @param cause the cause
     */
    void onCommitFailure(GlobalTransaction tx, Throwable cause);

    /**
     * 全局事务回回滚失败回调
     * On rollback failure.
     *
     * @param tx                the tx
     * @param originalException the originalException
     */
    void onRollbackFailure(GlobalTransaction tx, Throwable originalException);

    /**
     * 全局事务回回滚重试失败回调
     * On rollback retrying
     *
     * @param tx                the tx
     * @param originalException the originalException
     */
    void onRollbackRetrying(GlobalTransaction tx, Throwable originalException);
}
