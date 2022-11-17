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
package io.seata.spring.annotation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import io.seata.common.util.CollectionUtils;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationCache;
import io.seata.config.ConfigurationChangeEvent;
import io.seata.config.ConfigurationChangeListener;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.rpc.ShutdownHook;
import io.seata.core.rpc.netty.RmNettyRemotingClient;
import io.seata.core.rpc.netty.TmNettyRemotingClient;
import io.seata.rm.RMClient;
import io.seata.spring.annotation.datasource.SeataAutoDataSourceProxyCreator;
import io.seata.spring.annotation.scannercheckers.PackageScannerChecker;
import io.seata.spring.tcc.TccActionInterceptor;
import io.seata.spring.util.OrderUtil;
import io.seata.spring.util.SpringProxyUtils;
import io.seata.spring.util.TCCBeanParserUtils;
import io.seata.tm.TMClient;
import io.seata.tm.api.FailureHandler;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.AdvisedSupport;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

import static io.seata.common.DefaultValues.DEFAULT_DISABLE_GLOBAL_TRANSACTION;
import static io.seata.common.DefaultValues.DEFAULT_TX_GROUP;
import static io.seata.common.DefaultValues.DEFAULT_TX_GROUP_OLD;

/**
 * The type Global transaction scanner.
 * 全局事务扫描器
 * 1. TM、RM注册
 *  它实现InitializingBean接口, 在spring容器创建bean时，会调用实现类的{@link #afterPropertiesSet()}
 *  内部调用{@link #initClient()}方法中，进行TM、RM注册
 * 2.引入全局事务拦截器{@link GlobalTransactionalInterceptor}
 *  全局事务扫描器继承了AbstractAutoProxyCreator，是个后置处理器，并重写了wrapIfNecessary方法
 *  在spring容器创建bean时，会调用postProcessAfterInitialization后置处理器方法，执行实现类的{@link #wrapIfNecessary(Object, String, Object)}
 *  在方法中引入了全局事务拦截器{@link GlobalTransactionalInterceptor}
 * 3.客户端请求时调用拦截器invoke方法，发起全局事务
 *  在客户端发起请求时, 会被上面的全局事务拦截器给拦截到, 执行{@link GlobalTransactionalInterceptor#invoke(MethodInvocation)}方法,
 *  调用{@link GlobalTransactionalInterceptor#handleGlobalTransaction(MethodInvocation, AspectTransactional)}方法, 发起全局事务
 *
 * @author slievrly
 */
public class GlobalTransactionScanner extends AbstractAutoProxyCreator
        implements ConfigurationChangeListener, InitializingBean, ApplicationContextAware, DisposableBean {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalTransactionScanner.class);

    private static final int AT_MODE = 1;
    private static final int MT_MODE = 2;

    private static final int ORDER_NUM = 1024;
    private static final int DEFAULT_MODE = AT_MODE + MT_MODE;

    private static final String SPRING_TRANSACTION_INTERCEPTOR_CLASS_NAME = "org.springframework.transaction.interceptor.TransactionInterceptor";

    private static final Set<String> PROXYED_SET = new HashSet<>();
    private static final Set<String> EXCLUDE_BEAN_NAME_SET = new HashSet<>();
    private static final Set<ScannerChecker> SCANNER_CHECKER_SET = new LinkedHashSet<>();

    private static ConfigurableListableBeanFactory beanFactory;

    /**
     * 拦截器
     */
    private MethodInterceptor interceptor;

    /**
     * 全局拦截器
     * @see GlobalTransactionalInterceptor
     */
    private MethodInterceptor globalTransactionalInterceptor;

    private final String applicationId;
    private final String txServiceGroup;
    private final int mode;
    private static String accessKey;
    private static String secretKey;
    private volatile boolean disableGlobalTransaction = ConfigurationFactory.getInstance().getBoolean(
            ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION, DEFAULT_DISABLE_GLOBAL_TRANSACTION);
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private final FailureHandler failureHandlerHook;

    private ApplicationContext applicationContext;


    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param txServiceGroup the tx service group
     */
    public GlobalTransactionScanner(String txServiceGroup) {
        this(txServiceGroup, txServiceGroup, DEFAULT_MODE);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param txServiceGroup the tx service group
     * @param mode           the mode
     */
    public GlobalTransactionScanner(String txServiceGroup, int mode) {
        this(txServiceGroup, txServiceGroup, mode);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId  the application id
     * @param txServiceGroup the default server group
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup) {
        this(applicationId, txServiceGroup, DEFAULT_MODE);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId  the application id
     * @param txServiceGroup the tx service group
     * @param mode           the mode
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup, int mode) {
        this(applicationId, txServiceGroup, mode, null);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId      the application id
     * @param txServiceGroup     the tx service group
     * @param failureHandlerHook the failure handler hook
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup, FailureHandler failureHandlerHook) {
        this(applicationId, txServiceGroup, DEFAULT_MODE, failureHandlerHook);
    }

    /**
     * Instantiates a new Global transaction scanner.
     *
     * @param applicationId      the application id
     * @param txServiceGroup     the tx service group
     * @param mode               the mode
     * @param failureHandlerHook the failure handler hook
     */
    public GlobalTransactionScanner(String applicationId, String txServiceGroup, int mode,
                                    FailureHandler failureHandlerHook) {
        setOrder(ORDER_NUM);
        setProxyTargetClass(true);
        this.applicationId = applicationId;
        this.txServiceGroup = txServiceGroup;
        this.mode = mode;
        this.failureHandlerHook = failureHandlerHook;
    }

    /**
     * Sets access key.
     *
     * @param accessKey the access key
     */
    public static void setAccessKey(String accessKey) {
        GlobalTransactionScanner.accessKey = accessKey;
    }

    /**
     * Sets secret key.
     *
     * @param secretKey the secret key
     */
    public static void setSecretKey(String secretKey) {
        GlobalTransactionScanner.secretKey = secretKey;
    }

    @Override
    public void destroy() {
        ShutdownHook.getInstance().destroyAll();
    }

    private void initClient() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Initializing Global Transaction Clients ... ");
        }
        if (DEFAULT_TX_GROUP_OLD.equals(txServiceGroup)) {
            LOGGER.warn("the default value of seata.tx-service-group: {} has already changed to {} since Seata 1.5, " +
                    "please change your default configuration as soon as possible " +
                    "and we don't recommend you to use default tx-service-group's value provided by seata",
                    DEFAULT_TX_GROUP_OLD, DEFAULT_TX_GROUP);
        }
        if (StringUtils.isNullOrEmpty(applicationId) || StringUtils.isNullOrEmpty(txServiceGroup)) {
            throw new IllegalArgumentException(String.format("applicationId: %s, txServiceGroup: %s", applicationId, txServiceGroup));
        }
        //init TM 初始化事务管理器
        TMClient.init(applicationId, txServiceGroup, accessKey, secretKey);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Transaction Manager Client is initialized. applicationId[{}] txServiceGroup[{}]", applicationId, txServiceGroup);
        }
        //init RM 初始化资源管理器
        RMClient.init(applicationId, txServiceGroup);
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Resource Manager is initialized. applicationId[{}] txServiceGroup[{}]", applicationId, txServiceGroup);
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Global Transaction Clients are initialized. ");
        }
        registerSpringShutdownHook();

    }

    private void registerSpringShutdownHook() {
        if (applicationContext instanceof ConfigurableApplicationContext) {
            ((ConfigurableApplicationContext) applicationContext).registerShutdownHook();
            ShutdownHook.removeRuntimeShutdownHook();
        }
        ShutdownHook.getInstance().addDisposable(TmNettyRemotingClient.getInstance(applicationId, txServiceGroup));
        ShutdownHook.getInstance().addDisposable(RmNettyRemotingClient.getInstance(applicationId, txServiceGroup));
    }

    /**
     * 全局事务扫描器继承了AbstractAutoProxyCreator，是个后置处理器，并重写了wrapIfNecessary方法, 表示需要AOP代理/增强
     * 在spring容器创建bean时，会调用postProcessAfterInitialization后置处理器方法，然后执行wrapIfNecessary
     * 引入全局事务拦截器GlobalTransactionalInterceptor, 在客户端发起请求时会调用拦截器invoke方法，发起全局事务
     * @see GlobalTransactionalInterceptor#invoke(MethodInvocation)
     *
     * 以下将进行扫描，并添加相应的拦截器
     * The following will be scanned, and added corresponding interceptor:
     *
     * TM:
     * @see io.seata.spring.annotation.GlobalTransactional // TM annotation
     * Corresponding interceptor:
     * @see io.seata.spring.annotation.GlobalTransactionalInterceptor#handleGlobalTransaction(MethodInvocation, AspectTransactional) // TM handler
     *
     * GlobalLock:
     * @see io.seata.spring.annotation.GlobalLock // GlobalLock annotation
     * Corresponding interceptor:
     * @see io.seata.spring.annotation.GlobalTransactionalInterceptor#handleGlobalLock(MethodInvocation, GlobalLock)  // GlobalLock handler
     *
     * TCC mode:
     * @see io.seata.rm.tcc.api.LocalTCC // TCC annotation on interface
     * @see io.seata.rm.tcc.api.TwoPhaseBusinessAction // TCC annotation on try method
     * @see io.seata.rm.tcc.remoting.RemotingParser // Remote TCC service parser
     * Corresponding interceptor:
     * @see io.seata.spring.tcc.TccActionInterceptor // the interceptor of TCC mode
     */
    @Override
    protected Object wrapIfNecessary(Object bean, String beanName, Object cacheKey) {
        // do checkers
        if (!doCheckers(bean, beanName)) {
            return bean;
        }

        try {
            synchronized (PROXYED_SET) {
                if (PROXYED_SET.contains(beanName)) {
                    return bean;
                }
                interceptor = null;
                //check TCC proxy 检查是否是TCC模式, 如果是, 则添加TCC拦截器
                if (TCCBeanParserUtils.isTccAutoProxy(bean, beanName, applicationContext)) {
                    // init tcc fence clean task if enable useTccFence
                    TCCBeanParserUtils.initTccFenceCleanTask(TCCBeanParserUtils.getRemotingDesc(beanName), applicationContext);
                    //TCC interceptor, proxy bean of sofa:reference/dubbo:reference, and LocalTCC
                    interceptor = new TccActionInterceptor(TCCBeanParserUtils.getRemotingDesc(beanName));
                    ConfigurationCache.addConfigListener(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                            (ConfigurationChangeListener)interceptor);
                } else {
                    Class<?> serviceInterface = SpringProxyUtils.findTargetClass(bean);
                    Class<?>[] interfacesIfJdk = SpringProxyUtils.findInterfaces(bean);
                    /**
                     * 判断是否存在 {@link GlobalTransactional} 或 {@link GlobalLock} 注解, 如果不存在, 则直接返回不代理
                     */
                    if (!existsAnnotation(new Class[]{serviceInterface})
                        && !existsAnnotation(interfacesIfJdk)) {
                        return bean;
                    }
                    // 如果全局事务拦截器为空, 实例化一个
                    if (globalTransactionalInterceptor == null) {
                        // 指定全局事务拦截器
                        globalTransactionalInterceptor = new GlobalTransactionalInterceptor(failureHandlerHook);
                        ConfigurationCache.addConfigListener(
                                ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                                (ConfigurationChangeListener)globalTransactionalInterceptor);
                    }
                    /**
                     * 对存在 {@link GlobalTransactional} 或 {@link GlobalLock} 注解的方法, 添加全局事务拦截器
                     * 在这些被拦截的方法被调用时, 会执行全局事务拦截器的invoke方法
                     * @see GlobalTransactionalInterceptor#invoke(MethodInvocation)
                     */
                    interceptor = globalTransactionalInterceptor;
                }

                LOGGER.info("Bean[{}] with name [{}] would use interceptor [{}]", bean.getClass().getName(), beanName, interceptor.getClass().getName());
                // 检查是否是代理对象
                if (!AopUtils.isAopProxy(bean)) {
                    /**
                     * 调用Spring代理（父级）
                     * todo 存疑: 是否会在这里调用{@link SeataAutoDataSourceProxyCreator#wrapIfNecessary(Object, String, Object)}?
                     */
                    bean = super.wrapIfNecessary(bean, beanName, cacheKey);
                } else {
                    // 已经是代理对象，反射获取代理类中的已经存在的拦截器组合，然后添加到该集合当中
                    AdvisedSupport advised = SpringProxyUtils.getAdvisedSupport(bean);
                    Advisor[] advisor = buildAdvisors(beanName, getAdvicesAndAdvisorsForBean(null, null, null));
                    int pos;
                    // 遍历将bean的拦截器进行排序
                    for (Advisor avr : advisor) {
                        // Find the position based on the advisor's order, and add to advisors by pos
                        // 根据拦截器的顺序找到下标位置，并通过位置添加到拦截器中
                        pos = findAddSeataAdvisorPosition(advised, avr);
                        advised.addAdvisor(pos, avr);
                    }
                }
                // 将Bean添加到已代理的Set集合中
                PROXYED_SET.add(beanName);
                return bean;
            }
        } catch (Exception exx) {
            throw new RuntimeException(exx);
        }
    }

    private boolean doCheckers(Object bean, String beanName) {
        if (PROXYED_SET.contains(beanName) || EXCLUDE_BEAN_NAME_SET.contains(beanName)
            || FactoryBean.class.isAssignableFrom(bean.getClass())) {
            return false;
        }

        if (!SCANNER_CHECKER_SET.isEmpty()) {
            for (ScannerChecker checker : SCANNER_CHECKER_SET) {
                try {
                    if (!checker.check(bean, beanName, beanFactory)) {
                        // failed check, do not scan this bean
                        return false;
                    }
                } catch (Exception e) {
                    LOGGER.error("Do check failed: beanName={}, checker={}",
                            beanName, checker.getClass().getSimpleName(), e);
                }
            }
        }

        return true;
    }


    //region the methods about findAddSeataAdvisorPosition  START

    /**
     * Find pos for `advised.addAdvisor(pos, avr);`
     *
     * @param advised      the advised
     * @param seataAdvisor the seata advisor
     * @return the pos
     */
    private int findAddSeataAdvisorPosition(AdvisedSupport advised, Advisor seataAdvisor) {
        // Get seataAdvisor's order and interceptorPosition
        int seataOrder = OrderUtil.getOrder(seataAdvisor);
        SeataInterceptorPosition seataInterceptorPosition = getSeataInterceptorPosition(seataAdvisor);

        // If the interceptorPosition is any, check lowest or highest.
        if (SeataInterceptorPosition.Any == seataInterceptorPosition) {
            if (seataOrder == Ordered.LOWEST_PRECEDENCE) {
                // the last position
                return advised.getAdvisors().length;
            } else if (seataOrder == Ordered.HIGHEST_PRECEDENCE) {
                // the first position
                return 0;
            }
        } else {
            // If the interceptorPosition is not any, compute position if has TransactionInterceptor.
            Integer position = computePositionIfHasTransactionInterceptor(advised, seataAdvisor, seataInterceptorPosition, seataOrder);
            if (position != null) {
                // the position before or after TransactionInterceptor
                return position;
            }
        }

        // Find position
        return this.findPositionInAdvisors(advised.getAdvisors(), seataAdvisor);
    }

    @Nullable
    private Integer computePositionIfHasTransactionInterceptor(AdvisedSupport advised, Advisor seataAdvisor, SeataInterceptorPosition seataInterceptorPosition, int seataOrder) {
        // Find the TransactionInterceptor's advisor, order and position
        Advisor otherAdvisor = null;
        Integer transactionInterceptorPosition = null;
        Integer transactionInterceptorOrder = null;
        for (int i = 0, l = advised.getAdvisors().length; i < l; ++i) {
            otherAdvisor = advised.getAdvisors()[i];
            if (isTransactionInterceptor(otherAdvisor)) {
                transactionInterceptorPosition = i;
                transactionInterceptorOrder = OrderUtil.getOrder(otherAdvisor);
                break;
            }
        }
        // If the TransactionInterceptor does not exist, return null
        if (transactionInterceptorPosition == null) {
            return null;
        }

        // Reset seataOrder if the seataOrder is not match the position
        Advice seataAdvice = seataAdvisor.getAdvice();
        if (SeataInterceptorPosition.AfterTransaction == seataInterceptorPosition && OrderUtil.higherThan(seataOrder, transactionInterceptorOrder)) {
            int newSeataOrder = OrderUtil.lower(transactionInterceptorOrder, 1);
            ((SeataInterceptor)seataAdvice).setOrder(newSeataOrder);
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("The {}'s order '{}' is higher or equals than {}'s order '{}' , reset {}'s order to lower order '{}'.",
                        seataAdvice.getClass().getSimpleName(), seataOrder,
                        otherAdvisor.getAdvice().getClass().getSimpleName(), transactionInterceptorOrder,
                        seataAdvice.getClass().getSimpleName(), newSeataOrder);
            }
            // the position after the TransactionInterceptor's advisor
            return transactionInterceptorPosition + 1;
        } else if (SeataInterceptorPosition.BeforeTransaction == seataInterceptorPosition && OrderUtil.lowerThan(seataOrder, transactionInterceptorOrder)) {
            int newSeataOrder = OrderUtil.higher(transactionInterceptorOrder, 1);
            ((SeataInterceptor)seataAdvice).setOrder(newSeataOrder);
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("The {}'s order '{}' is lower or equals than {}'s order '{}' , reset {}'s order to higher order '{}'.",
                        seataAdvice.getClass().getSimpleName(), seataOrder,
                        otherAdvisor.getAdvice().getClass().getSimpleName(), transactionInterceptorOrder,
                        seataAdvice.getClass().getSimpleName(), newSeataOrder);
            }
            // the position before the TransactionInterceptor's advisor
            return transactionInterceptorPosition;
        }

        return null;
    }

    private int findPositionInAdvisors(Advisor[] advisors, Advisor seataAdvisor) {
        Advisor advisor;
        for (int i = 0, l = advisors.length; i < l; ++i) {
            advisor = advisors[i];
            if (OrderUtil.higherOrEquals(seataAdvisor, advisor)) {
                // the position before the current advisor
                return i;
            }
        }

        // the last position, after all the advisors
        return advisors.length;
    }

    private SeataInterceptorPosition getSeataInterceptorPosition(Advisor seataAdvisor) {
        Advice seataAdvice = seataAdvisor.getAdvice();
        if (seataAdvice instanceof SeataInterceptor) {
            return ((SeataInterceptor)seataAdvice).getPosition();
        } else {
            return SeataInterceptorPosition.Any;
        }
    }

    private boolean isTransactionInterceptor(Advisor advisor) {
        return SPRING_TRANSACTION_INTERCEPTOR_CLASS_NAME.equals(advisor.getAdvice().getClass().getName());
    }

    //endregion the methods about findAddSeataAdvisorPosition  END


    private boolean existsAnnotation(Class<?>[] classes) {
        if (CollectionUtils.isNotEmpty(classes)) {
            for (Class<?> clazz : classes) {
                if (clazz == null) {
                    continue;
                }
                GlobalTransactional trxAnno = clazz.getAnnotation(GlobalTransactional.class);
                if (trxAnno != null) {
                    return true;
                }
                Method[] methods = clazz.getMethods();
                for (Method method : methods) {
                    trxAnno = method.getAnnotation(GlobalTransactional.class);
                    if (trxAnno != null) {
                        return true;
                    }

                    GlobalLock lockAnno = method.getAnnotation(GlobalLock.class);
                    if (lockAnno != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private MethodDesc makeMethodDesc(GlobalTransactional anno, Method method) {
        return new MethodDesc(anno, method);
    }

    @Override
    protected Object[] getAdvicesAndAdvisorsForBean(Class beanClass, String beanName, TargetSource customTargetSource)
            throws BeansException {
        return new Object[]{interceptor};
    }

    /**
     * spring 容器启动时, 会执行此方法
     * 微服务如果需要使用seata, 就必须引入seata的包, 在微服务启动时, 作为seata服务的客户端, 会通过自动配置, 注入此全局事务扫描器, 调用此方法.
     */
    @Override
    public void afterPropertiesSet() {
        if (disableGlobalTransaction) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Global transaction is disabled.");
            }
            ConfigurationCache.addConfigListener(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                    (ConfigurationChangeListener)this);
            return;
        }
        if (initialized.compareAndSet(false, true)) {
            /**
             * 初始化客户端: 事务管理器/资源管理器/分支事务
             */
            initClient();
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
        this.setBeanFactory(applicationContext);
    }

    @Override
    public void onChangeEvent(ConfigurationChangeEvent event) {
        if (ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION.equals(event.getDataId())) {
            disableGlobalTransaction = Boolean.parseBoolean(event.getNewValue().trim());
            if (!disableGlobalTransaction && initialized.compareAndSet(false, true)) {
                LOGGER.info("{} config changed, old value:true, new value:{}", ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION,
                        event.getNewValue());
                initClient();
                ConfigurationCache.removeConfigListener(ConfigurationKeys.DISABLE_GLOBAL_TRANSACTION, this);
            }
        }
    }

    public static void setBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        GlobalTransactionScanner.beanFactory = beanFactory;
    }

    public static void addScannablePackages(String... packages) {
        PackageScannerChecker.addScannablePackages(packages);
    }

    public static void addScannerCheckers(Collection<ScannerChecker> scannerCheckers) {
        if (CollectionUtils.isNotEmpty(scannerCheckers)) {
            scannerCheckers.remove(null);
            SCANNER_CHECKER_SET.addAll(scannerCheckers);
        }
    }

    public static void addScannerCheckers(ScannerChecker... scannerCheckers) {
        if (ArrayUtils.isNotEmpty(scannerCheckers)) {
            addScannerCheckers(Arrays.asList(scannerCheckers));
        }
    }

    public static void addScannerExcludeBeanNames(String... beanNames) {
        if (ArrayUtils.isNotEmpty(beanNames)) {
            EXCLUDE_BEAN_NAME_SET.addAll(Arrays.asList(beanNames));
        }
    }
}
