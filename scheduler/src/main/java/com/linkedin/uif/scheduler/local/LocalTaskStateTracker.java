package com.linkedin.uif.scheduler.local;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractIdleService;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.linkedin.uif.scheduler.Task;
import com.linkedin.uif.scheduler.TaskExecutor;
import com.linkedin.uif.scheduler.TaskStateTracker;
import com.linkedin.uif.configuration.WorkUnitState;
import com.linkedin.uif.configuration.ConfigurationKeys;

/**
 * An implementation of {@link com.linkedin.uif.scheduler.TaskStateTracker}
 * that reports {@link com.linkedin.uif.scheduler.TaskState}s to the
 * {@link LocalJobManager}.
 *
 * <p>
 *     This is the implementation used only in single-node mode.
 * </p>
 *
 * @author ynli
 */
public class LocalTaskStateTracker extends AbstractIdleService
        implements TaskStateTracker {

    private static final Log LOG = LogFactory.getLog(LocalJobManager.class);

    // This is used to retry failed tasks
    private final TaskExecutor taskExecutor;

    // This is used to schedule and run reporters for reporting state
    // and progress of running tasks
    private final ScheduledThreadPoolExecutor reporterExecutor;

    // Mapping between tasks and the task state reporters associated with them
    private final Map<String, ScheduledFuture<?>> scheduledReporters;

    // This is used to report final state when a task is completed
    private LocalJobManager jobManager;

    // Maximum number of task retries allowed
    private final int maxTaskRetries;

    public LocalTaskStateTracker(Properties properties, TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
        this.reporterExecutor = new ScheduledThreadPoolExecutor(
                Integer.parseInt(properties.getProperty(
                        ConfigurationKeys.TASK_STATE_TRACKER_THREAD_POOL_CORE_SIZE_KEY,
                        ConfigurationKeys.DEFAULT_TASK_STATE_TRACKER_THREAD_POOL_CORE_SIZE)));
        this.reporterExecutor.setMaximumPoolSize(
                Integer.parseInt(properties.getProperty(
                        ConfigurationKeys.TASK_STATE_TRACKER_THREAD_POOL_MAX_SIZE_KEY,
                        ConfigurationKeys.DEFAULT_TASK_STATE_TRACKER_THREAD_POOL_MAX_SIZE)));
        this.scheduledReporters = Maps.newHashMap();
        this.maxTaskRetries = Integer.parseInt(properties.getProperty(
                ConfigurationKeys.MAX_TASK_RETRIES_KEY,
                ConfigurationKeys.DEFAULT_MAX_TASK_RETRIES));
    }

    @Override
    protected void startUp() {
        LOG.info("Starting the local task state tracker");
    }

    @Override
    protected void shutDown() {
        LOG.info("Stopping the local task state tracker");
        this.reporterExecutor.shutdown();
    }

    @Override
    public void registerNewTask(Task task) {
        TaskStateReporter reporter = new TaskStateReporter(task);
        // Schedule a reporter to periodically report state and progress
        // of the given task
        this.scheduledReporters.put(
                task.getTaskId(),
                this.reporterExecutor.scheduleAtFixedRate(
                    reporter,
                    0,
                    task.getTaskContext().getStatusReportingInterval(),
                    TimeUnit.MILLISECONDS
                )
        );
    }

    @Override
    public void onTaskCompletion(Task task) {
        // Cancel the task state reporter associated with this task
        ScheduledFuture<?> scheduledReporter =
                this.scheduledReporters.remove(task.getTaskId());
        scheduledReporter.cancel(true);

        // Check the task state and handle task retry if task failed and
        // it has not reached the maxium number of retries
        WorkUnitState.WorkingState state = task.getTaskState().getWorkingState();
        if (state == WorkUnitState.WorkingState.FAILED &&
                task.getRetryCount() < this.maxTaskRetries) {

            this.taskExecutor.retry(task);
            return;
        }

        // At this point, the task is considered being completed.
        LOG.info(String.format("Task %s completed in %dms with state %s",
                task.getTaskId(), task.getTaskState().getTaskDuration(), state));
        this.jobManager.onTaskCompletion(task.getJobId(), task.getTaskState());
    }

    /**
     * Set the {@link LocalJobManager} used by this {@link TaskStateTracker}.
     *
     * @param jobManager {@link LocalJobManager}
     */
    public void setJobManager(LocalJobManager jobManager) {
        this.jobManager = jobManager;
    }

    /**
     * A class for reporting the state of a task while the task is running.
     */
    private static class TaskStateReporter implements Runnable {

        public final Task task;

        public TaskStateReporter(Task task) {
            this.task = task;
        }

        @Override
        public void run() {
            // TODO: Handling task state reporting
        }
    }
}