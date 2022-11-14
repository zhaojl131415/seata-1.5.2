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
package io.seata.rm.tcc.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * TCC annotation. TCC注解
 * Define a TCC interface, which added on the try method. 定义一个TCC接口，该接口添加到try方法上。
 * Must be used with `@LocalTCC`. 必须与 “@LocalTCC” 一起使用。
 *
 * @author zhangsen
 * @see io.seata.rm.tcc.api.LocalTCC // TCC annotation, which added on the TCC interface. It can't be left out.
 * @see io.seata.spring.annotation.GlobalTransactionScanner#wrapIfNecessary(Object, String, Object) // the scanner for TM, GlobalLock, and TCC mode
 * @see io.seata.spring.tcc.TccActionInterceptor // the interceptor of TCC mode
 * @see BusinessActionContext
 * @see BusinessActionContextUtil
 * @see BusinessActionContextParameter
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
@Inherited
public @interface TwoPhaseBusinessAction {

    /**
     * TCC Bean 名，必须全局唯一
     * TCC bean name, must be unique
     *
     * @return the string
     */
    String name();

    /**
     * 提交方法
     * commit method name
     *
     * @return the string
     */
    String commitMethod() default "commit";

    /**
     * 回滚方法
     * rollback method name
     *
     * @return the string
     */
    String rollbackMethod() default "rollback";

    /**
     * delay branch report while sharing params to tcc phase 2 to enhance performance
     *
     * @return isDelayReport
     */
    boolean isDelayReport() default false;

    /**
     * 是否使用TCC fence (非有效、非回滚、挂起), 其实就是通过执行日志来解决幂等、悬挂、空回滚问题
     *
     * 幂等问题: 执行完try方法后, 事务协调者会将提交/回滚的结果通知各分支事务. 但是在分支事务二阶段执行时, 如果遇到执行失败, 事务协调者收不到分支事务的执行结果, 事务协调者会重复发起提交/回滚操作, 直到二阶段执行结果成功. 导致数据不一致.
     *
     * 悬挂问题: 因为网络拥堵问题, 导致try方法一直在阻塞中未执行, 最后导致超时失败调用回滚方法, 执行完回滚之后, try方法这时候得到执行, 处理业务数据, 导致数据不一致.
     *
     * 空回滚问题: 因为网络或业务不合法等问题, 导致try操作失败, 实际并没有对业务数据进行修改, 但是因为try方法失败, 会执行回滚方法, 回滚方法中会处理修复数据的逻辑. 导致数据不一致.
     * 例: 转账操作中张三给李四转账100元, try方法中对张三余额进行扣款100, 提交方法将100转给李四, 回滚方法将100加回张三账户.
     * 但是如果try方法执行失败, 并未对张三扣款, 最后回滚却给张三账户加了100元. 这时就需要进行空回滚.
     *
     * whether use TCC fence (idempotent,non_rollback,suspend)
     *
     * @return the boolean
     */
    boolean useTCCFence() default false;

    /**
     * commit method's args
     *
     * @return the Class[]
     */
    Class<?>[] commitArgsClasses() default {BusinessActionContext.class};

    /**
     * rollback method's args
     *
     * @return the Class[]
     */
    Class<?>[] rollbackArgsClasses() default {BusinessActionContext.class};
}
