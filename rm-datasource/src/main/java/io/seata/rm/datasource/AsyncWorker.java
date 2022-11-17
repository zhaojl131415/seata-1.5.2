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
package io.seata.rm.datasource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Lists;
import io.seata.common.thread.NamedThreadFactory;
import io.seata.common.util.IOUtil;
import io.seata.common.util.StringUtils;
import io.seata.config.ConfigurationFactory;
import io.seata.core.model.BranchStatus;
import io.seata.rm.datasource.undo.UndoLogManager;
import io.seata.rm.datasource.undo.UndoLogManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.seata.common.DefaultValues.DEFAULT_CLIENT_ASYNC_COMMIT_BUFFER_LIMIT;
import static io.seata.core.constants.ConfigurationKeys.CLIENT_ASYNC_COMMIT_BUFFER_LIMIT;

/**
 * The type Async worker.
 *
 * @author sharajava
 */
public class AsyncWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncWorker.class);

    private static final int DEFAULT_RESOURCE_SIZE = 16;

    private static final int UNDOLOG_DELETE_LIMIT_SIZE = 1000;

    private static final int ASYNC_COMMIT_BUFFER_LIMIT = ConfigurationFactory.getInstance().getInt(
        CLIENT_ASYNC_COMMIT_BUFFER_LIMIT, DEFAULT_CLIENT_ASYNC_COMMIT_BUFFER_LIMIT);

    private final DataSourceManager dataSourceManager;

    /**
     * 分支事务异步提交队列
     */
    private final BlockingQueue<Phase2Context> commitQueue;

    private final ScheduledExecutorService scheduledExecutor;

    public AsyncWorker(DataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;

        LOGGER.info("Async Commit Buffer Limit: {}", ASYNC_COMMIT_BUFFER_LIMIT);
        commitQueue = new LinkedBlockingQueue<>(ASYNC_COMMIT_BUFFER_LIMIT);

        ThreadFactory threadFactory = new NamedThreadFactory("AsyncWorker", 2, true);
        scheduledExecutor = new ScheduledThreadPoolExecutor(2, threadFactory);
        /**
         * 延时周期任务: 执行分支事务提交
         */
        scheduledExecutor.scheduleAtFixedRate(this::doBranchCommitSafely, 10, 1000, TimeUnit.MILLISECONDS);
    }

    public BranchStatus branchCommit(String xid, long branchId, String resourceId) {
        Phase2Context context = new Phase2Context(xid, branchId, resourceId);
        /**
         * 将分支事务提交加入队列
         */
        addToCommitQueue(context);
        return BranchStatus.PhaseTwo_Committed;
    }

    /**
     * try add context to commitQueue directly, if fail(which means the queue is full),
     * then doBranchCommit urgently(so that the queue could be empty again) and retry this process.
     */
    private void addToCommitQueue(Phase2Context context) {
        // 加入阻塞队列, 加入成功, 则直接返回
        if (commitQueue.offer(context)) {
            return;
        }
        // 如果加入队列失败, 则将队列中的现有任务全部取出执行, 后将当前任务继续加入到队列中
        CompletableFuture.runAsync(this::doBranchCommitSafely, scheduledExecutor)
                .thenRun(() -> addToCommitQueue(context));
    }

    private void addAllToCommitQueue(List<Phase2Context> contexts) {
        for (Phase2Context context : contexts) {
            addToCommitQueue(context);
        }
    }

    void doBranchCommitSafely() {
        try {
            doBranchCommit();
        } catch (Throwable e) {
            LOGGER.error("Exception occur when doing branch commit", e);
        }
    }

    private void doBranchCommit() {
        if (commitQueue.isEmpty()) {
            return;
        }

        // transfer all context currently received to this list 将阻塞队列中的所有数据转移到List
        List<Phase2Context> allContexts = new LinkedList<>();
        commitQueue.drainTo(allContexts);

        // group context by their resourceId 按资源id分组
        Map<String, List<Phase2Context>> groupedContexts = groupedByResourceId(allContexts);
        // 遍历处理分支事务提交
        groupedContexts.forEach(this::dealWithGroupedContexts);
    }

    /**
     * 分组
     * @param contexts
     * @return
     */
    Map<String, List<Phase2Context>> groupedByResourceId(List<Phase2Context> contexts) {
        Map<String, List<Phase2Context>> groupedContexts = new HashMap<>(DEFAULT_RESOURCE_SIZE);
        contexts.forEach(context -> {
            if (StringUtils.isBlank(context.resourceId)) {
                LOGGER.warn("resourceId is empty, resource:{}", context);
                return;
            }
            List<Phase2Context> group = groupedContexts.computeIfAbsent(context.resourceId, key -> new LinkedList<>());
            group.add(context);
        });
        return groupedContexts;
    }

    /**
     * 处理分支事务提交
     * @param resourceId
     * @param contexts
     */
    private void dealWithGroupedContexts(String resourceId, List<Phase2Context> contexts) {
        if (StringUtils.isBlank(resourceId)) {
            //ConcurrentHashMap required notNull key
            LOGGER.warn("resourceId is empty and will skip.");
            return;
        }
        DataSourceProxy dataSourceProxy = dataSourceManager.get(resourceId);
        if (dataSourceProxy == null) {
            LOGGER.warn("failed to find resource for {} and requeue", resourceId);
            addAllToCommitQueue(contexts);
            return;
        }

        Connection conn = null;
        try {
            conn = dataSourceProxy.getPlainConnection();
            // 获取UndoLog处理器
            UndoLogManager undoLogManager = UndoLogManagerFactory.getUndoLogManager(dataSourceProxy.getDbType());

            // split contexts into several lists, with each list contain no more element than limit size
            List<List<Phase2Context>> splitByLimit = Lists.partition(contexts, UNDOLOG_DELETE_LIMIT_SIZE);
            for (List<Phase2Context> partition : splitByLimit) {
                // 遍历执行, 删除UndoLog后提交事务
                deleteUndoLog(conn, undoLogManager, partition);
            }
        } catch (SQLException sqlExx) {
            addAllToCommitQueue(contexts);
            LOGGER.error("failed to get connection for async committing on {} and requeue", resourceId, sqlExx);
        } finally {
            IOUtil.close(conn);
        }

    }

    private void deleteUndoLog(final Connection conn, UndoLogManager undoLogManager, List<Phase2Context> contexts) {
        Set<String> xids = new LinkedHashSet<>(contexts.size());
        Set<Long> branchIds = new LinkedHashSet<>(contexts.size());
        contexts.forEach(context -> {
            xids.add(context.xid);
            branchIds.add(context.branchId);
        });

        try {
            // 批量删除UndoLog
            undoLogManager.batchDeleteUndoLog(xids, branchIds, conn);
            if (!conn.getAutoCommit()) {
                // 分支事务提交
                conn.commit();
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to batch delete undo log", e);
            try {
                conn.rollback();
                addAllToCommitQueue(contexts);
            } catch (SQLException rollbackEx) {
                LOGGER.error("Failed to rollback JDBC resource after deleting undo log failed", rollbackEx);
            }
        }
    }

    static class Phase2Context {

        /**
         * AT Phase 2 context
         * @param xid             the xid
         * @param branchId        the branch id
         * @param resourceId      the resource id
         */
        public Phase2Context(String xid, long branchId, String resourceId) {
            this.xid = xid;
            this.branchId = branchId;
            this.resourceId = resourceId;
        }

        /**
         * The Xid.
         */
        String xid;
        /**
         * The Branch id.
         */
        long branchId;
        /**
         * The Resource id.
         */
        String resourceId;

        @Override
        public String toString() {
            return "Phase2Context{" + "xid='" + xid + '\'' + ", branchId=" + branchId + ", resourceId='" + resourceId
                + '\'' + '}';
        }
    }
}
