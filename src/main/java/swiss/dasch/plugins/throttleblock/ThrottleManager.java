package swiss.dasch.plugins.throttleblock;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.BulkChange;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Job;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.Queue.BlockedItem;
import hudson.model.Queue.BuildableItem;
import hudson.model.Queue.Executable;
import hudson.model.Queue.LeftItem;
import hudson.model.Queue.WaitingItem;
import hudson.model.queue.WorkUnit;
import hudson.model.queue.WorkUnitContext;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;

@Symbol("throttleBlock")
@Extension
public class ThrottleManager extends GlobalConfiguration {

	private static final Logger LOGGER = Logger.getLogger(ThrottleManager.class.getName());

	public static enum QueueResult {
		OK, NO_LOCK_ENTERED, BLOCKED_BY_QUEUED_ITEM, BLOCKED_BY_WORK_UNIT, BLOCKED_BY_BLOCKING_STATE,
		BLOCKED_BY_NO_BLOCKING_STATE
	}

	private final Map<Long, QueueBlockingState> queueBlockingStates = new HashMap<>();
	private final List<RunBlockingState> runBlockingStates = new ArrayList<>();

	private long timeoutAfterUnlock;

	public ThrottleManager() {
		resetProperties();
		load();
	}

	private void resetProperties() {
		timeoutAfterUnlock = 500;
	}

	public long getTimeoutAfterUnlock() {
		return timeoutAfterUnlock;
	}

	@DataBoundSetter
	public void setTimeoutAfterUnlock(long timeoutAfterUnlock) {
		this.timeoutAfterUnlock = timeoutAfterUnlock;
	}

	public QueueResult tryQueueItem(Node node, Queue.Item item) {
		return tryQueue(node, item, null, false);
	}

	public QueueResult tryQueueItem(Node node, Queue.Item item, boolean isSynchronizedWithQueue) {
		return tryQueue(node, item, null, isSynchronizedWithQueue);
	}

	public QueueResult tryQueueLock(Node node, RunBlockingState runState) {
		return tryQueue(node, null, runState, false);
	}

	private QueueResult tryQueue(Node node, Queue.Item item, RunBlockingState runState,
			boolean isSynchronizedWithQueue) {
		Run<?, ?> run = null;

		QueueBlockingState queueState = null;
		boolean wasPotentiallyPendingOrRunning = false;
		boolean isPotentiallyPendingOrRunning = false;

		IThrottleLock dequeuedLock = null;

		try {
			synchronized (this) {
				boolean changed = false;

				try {
					run = runState != null ? runState.getRun() : null;
					queueState = item == null ? null : queueBlockingStates.get(item.getId());

					// Reset queue pending state to false because the item
					// has gone backwards in the queue cycle, it can't be
					// pending anymore
					if (queueState != null) {
						wasPotentiallyPendingOrRunning = queueState.isPotentiallyPendingOrRunning;
						queueState.isPotentiallyPendingOrRunning = isPotentiallyPendingOrRunning = false;
						changed = wasPotentiallyPendingOrRunning != false;
					}

					// Check if there is anything blocking and
					// if so return the reason
					QueueResult result = this.checkBlocked(node, run, item, isSynchronizedWithQueue);
					if (result != QueueResult.OK) {
						return result;
					}

					// If all checks are passed then this item or
					// state may proceed

					// If the item is a BuildableItem or task is
					// a FlyweightTask then it may become a pending
					// or running item without canRun/tryQueue being
					// called again
					if (queueState != null
							&& (item instanceof BuildableItem || item.task instanceof Queue.FlyweightTask)) {
						queueState.isPotentiallyPendingOrRunning = isPotentiallyPendingOrRunning = true;
						changed = true;
					}

					// If state is specified then try to dequeue a
					// lock
					if (runState != null) {
						dequeuedLock = runState.tryDequeueLock();
						if (dequeuedLock == null) {
							return QueueResult.NO_LOCK_ENTERED;
						} else {
							changed = true;
						}
					}

					return QueueResult.OK;
				} finally {
					if (changed) {
						save();
					}
				}
			}
		} finally {
			// If a lock was dequeued then it must also
			// be entered
			if (dequeuedLock != null) {
				dequeuedLock.enter();
			}

			// If an item goes from pending to non pending then
			// this may allow other blocking state locks to enter
			// -> yield
			if (wasPotentiallyPendingOrRunning && !isPotentiallyPendingOrRunning) {
				yield();
			}
		}
	}

	private synchronized QueueResult checkBlocked(Node node, Run<?, ?> run, Queue.Item item,
			boolean isSynchronizedWithQueue) {
		Queue.Task task = item != null ? item.task : null;

		// Don't block task if it is not configured to throttle
		if (run == null && !isThrottlingBeforeRun(task)) {
			return QueueResult.OK;
		}

		// Never block continued/resuming tasks
		if (run == null && task instanceof ContinuedTask && ((ContinuedTask) task).isContinued()) {
			return QueueResult.OK;
		}

		Queue queue = Queue.getInstance();

		// If there are any other items queued then we
		// must return false because those queued items
		// may start being executed outside of our control
		for (long id : queueBlockingStates.keySet()) {
			if ((item == null || id != item.getId()) && (run == null || id != run.getQueueId())
					&& queueBlockingStates.get(id).isPotentiallyBlocking()) {

				// If we're synchronized with the queue then
				// we can skip an item if it is not pending
				// because it'll have to be checked again
				// before moving to the pending queue
				if (isSynchronizedWithQueue) {
					Queue.Item queueItem = queue.getItem(id);

					if (queueItem instanceof BuildableItem && !((BuildableItem) queueItem).isPending()) {
						continue;
					}
				}

				return QueueResult.BLOCKED_BY_QUEUED_ITEM;
			}
		}

		// Check if there are any runs that are
		// actually blocking
		for (RunBlockingState otherState : runBlockingStates) {
			if ((run == null || otherState.getRun() != run) && otherState.isBlocking(getTimeoutAfterUnlock())) {
				return QueueResult.BLOCKED_BY_BLOCKING_STATE;
			}
		}

		Computer computer = node.toComputer();
		if (computer != null) {
			for (Executor executor : computer.getExecutors()) {

				// Check if there are any runs on this
				// computer that are running and don't
				// have a blocking state yet -> must
				// assume they haven't checked out yet
				Executable executable = executor.getCurrentExecutable();
				if (executable != null) {
					Run<?, ?> executorRun = null;
					if (executable instanceof Run<?, ?>) {
						executorRun = (Run<?, ?>) executable;
					} else {
						executable = executable.getParentExecutable();
						if (executable instanceof Run<?, ?>) {
							executorRun = (Run<?, ?>) executable;
						}
					}

					if (executorRun != null && isThrottlingBeforeRun(executorRun.getParent())
							&& getRunBlockingState(executorRun) == null) {
						return QueueResult.BLOCKED_BY_NO_BLOCKING_STATE;
					}
				} else {
					// If there is no executable yet but a work unit
					// (i.e. the item has left the queue but hasn't
					// started executing yet), then we must also block
					// unless this work unit is the same as the item
					// trying to be queued
					WorkUnit workUnit = executor.getCurrentWorkUnit();
					if (workUnit != null && (item == null || workUnit.context.item.getId() != item.getId())) {
						return QueueResult.BLOCKED_BY_WORK_UNIT;
					}
				}

			}
		}

		return QueueResult.OK;
	}

	public boolean createRunBlockingState(Run<?, ?> run) {
		return createRunBlockingState(run, null);
	}

	public boolean createRunBlockingState(Run<?, ?> run, IThrottleLock lock) {
		RunBlockingState state = null;
		boolean isNewState = false;

		boolean changed = false;

		synchronized (this) {
			for (RunBlockingState s : runBlockingStates) {
				if (s.getRun() == run) {
					state = s;
					break;
				}
			}

			// Create a new blocking state if one doesn't
			// exist already
			if (state == null) {
				state = new RunBlockingState(run);
				runBlockingStates.add(state);
				isNewState = true;
				changed = true;
			}

			if (lock != null) {
				changed |= state.queueLock(lock);
			}

			if (changed) {
				save();
			}
		}

		if (lock != null) {
			// Try to enter state locks immediately
			// because e.g. if this is the only blocking
			// state and doesn't have any locks yet, then
			// the lock may enter immediately
			tryEnterLock(state);
		} else if (isNewState) {
			yield();
			scheduleMaintenanceAfterTimeout();
		}

		return isNewState;
	}

	public boolean tryEnterLock(RunBlockingState state) {
		Run<?, ?> run = state.getRun();

		if (run != null) {
			Executor executor = run.getExecutor();

			if (executor != null) {
				Node node = executor.getOwner().getNode();

				if (node != null) {
					return tryQueueLock(node, state) == QueueResult.OK;
				}
			}
		}

		return false;
	}

	public void yield() {
		List<RunBlockingState> blockingStatesCopy;

		synchronized (this) {
			blockingStatesCopy = new ArrayList<>(runBlockingStates);
		}

		for (RunBlockingState state : blockingStatesCopy) {
			if (tryEnterLock(state)) {
				break;
			}
		}
	}

	public void scheduleMaintenanceAfterTimeout() {
		Timer.get().schedule(() -> {
			yield();
			Queue.getInstance().scheduleMaintenance();
		}, this.getTimeoutAfterUnlock() + 10, TimeUnit.MILLISECONDS);
	}

	public synchronized RunBlockingState getRunBlockingState(Run<?, ?> run) {
		for (RunBlockingState state : runBlockingStates) {
			if (state.getRun() == run) {
				return state;
			}
		}
		return null;
	}

	public boolean deleteRunBlockingState(Run<?, ?> run) {
		return deleteRunBlockingState(run, state -> true);
	}

	public boolean deleteRunBlockingState(Run<?, ?> run, Predicate<RunBlockingState> condition) {
		boolean deleted = false;

		synchronized (this) {
			Iterator<RunBlockingState> it = runBlockingStates.iterator();
			while (it.hasNext()) {
				RunBlockingState state = it.next();
				if (state.getRun() == run && condition.test(state)) {
					it.remove();
					deleted = true;
				}
			}

			if (deleted) {
				save();
			}
		}

		if (deleted) {
			yield();
		}

		return deleted;
	}

	private synchronized void createQueueBlockingState(long id) {
		queueBlockingStates.putIfAbsent(id, new QueueBlockingState());
	}

	private void deleteQueueBlockingState(long id) {
		boolean removed;

		synchronized (this) {
			removed = queueBlockingStates.remove(id) != null;

			if (removed) {
				save();
			}
		}

		if (removed) {
			yield();
		}
	}

	private synchronized void setQueueBlockingStateWork(long id, WorkUnitContext work) {
		QueueBlockingState state = queueBlockingStates.get(id);
		if (state != null && state.work != work) {
			state.work = work;

			// Work might be immediately done
			yield();
			scheduleMaintenanceAfterTimeout();
		}
	}

	public void maintain() {
		Queue queue = Jenkins.get().getQueue();

		Queue.withLock(() -> {
			synchronized (this) {
				boolean changed = false;

				Iterator<Long> it = queueBlockingStates.keySet().iterator();

				while (it.hasNext()) {
					long id = it.next();

					Queue.Item item = queue.getItem(id);

					if (item == null || item instanceof LeftItem) {
						QueueBlockingState state = queueBlockingStates.get(id);

						// If item is no longer queued and work == null
						// then something went wrong because the item never
						// properly left the queue and started running, so
						// it can be removed. Otherwise just remove if
						// the state has expired and is of no more use
						if (state.work == null || state.isExpired()) {
							it.remove();
							changed = true;
						}
					} else if (item instanceof BuildableItem && item.task instanceof Queue.FlyweightTask == false
							&& !((BuildableItem) item).isPending()) {
						// If the item is not pending here then it will
						// have to go through tryQueue again before it
						// starts running, so it needn't block others.
						// This is necessary to give blocking states a
						// chance to unblock if there are no executors
						// available causing items to remain in the
						// buildable queue.
						QueueBlockingState state = queueBlockingStates.get(id);
						if (state.isPotentiallyPendingOrRunning) {
							state.isPotentiallyPendingOrRunning = false;
							changed = true;
						}
					}
				}

				if (changed) {
					save();
				}
			}
		});

		synchronized (this) {
			boolean changed = false;

			Iterator<RunBlockingState> it = runBlockingStates.iterator();
			while (it.hasNext()) {
				RunBlockingState state = it.next();
				if (state.tryClearOnExpiration(getTimeoutAfterUnlock())) {
					it.remove();
					changed = true;
				}
			}

			if (changed) {
				save();
			}
		}

		yield();
	}

	public static boolean isThrottlingBeforeRun(Queue.Task task) {
		return task instanceof Job<?, ?> && isThrottlingBeforeRun((Job<?, ?>) task);
	}

	public static boolean isThrottlingBeforeRun(Job<?, ?> job) {
		ThrottleJobProperty property = job.getProperty(ThrottleJobProperty.class);
		return property != null && property.getRunThrottle() != null;
	}

	@Override
	public boolean configure(StaplerRequest req, JSONObject json) {
		resetProperties();

		try (BulkChange bc = new BulkChange(this)) {
			req.bindJSON(this, json);
			bc.commit();
		} catch (IOException ex) {
			LOGGER.log(Level.WARNING, "Exception during BulkChange.", ex);
			return false;
		}

		return true;
	}

	public static ThrottleManager get() {
		return (ThrottleManager) Jenkins.get().getDescriptorOrDie(ThrottleManager.class);
	}

	private static class QueueBlockingState implements Serializable {

		private static final long serialVersionUID = -1543503806460682092L;

		private volatile boolean isPotentiallyPendingOrRunning;
		private transient WorkUnitContext work;

		private boolean isPotentiallyBlocking() {
			return isPotentiallyPendingOrRunning && !isExpired();
		}

		private boolean isExpired() {
			if (work != null) {
				// If the job isn't configured to enter the
				// throttle before running then this queue
				// state isn't needed at all
				if (!ThrottleManager.isThrottlingBeforeRun(work.task)) {
					return true;
				}

				// If the future is done then the run has
				// already exited, so the queue state is
				// certainly not needed anymore...
				if (work.future.isDone()) {
					return true;
				}

				// If there already is an executable and it
				// already has a blocking state or is already
				// finished then we don't need the queue state
				// anymore
				Executable executable = work.getPrimaryWorkUnit().getExecutable();
				if (executable != null
						&& (executable instanceof Run<?, ?> == false || !((Run<?, ?>) executable).isBuilding()
								|| ThrottleManager.get().getRunBlockingState((Run<?, ?>) executable) != null)) {
					return true;
				}
			}
			return false;
		}

	}

	@Extension
	public static class QueueListener extends hudson.model.queue.QueueListener {

		private void checkAndCreateQueueState(Queue.Item item) {
			// Queue state is only needed to throttle jobs
			// before running
			if (!ThrottleManager.isThrottlingBeforeRun(item.task)) {
				return;
			}

			ThrottleManager.get().createQueueBlockingState(item.getId());
		}

		@Override
		public void onEnterBlocked(BlockedItem item) {
			checkAndCreateQueueState(item);
		}

		@Override
		public void onEnterWaiting(WaitingItem item) {
			checkAndCreateQueueState(item);
		}

		@Override
		public void onEnterBuildable(BuildableItem item) {
			checkAndCreateQueueState(item);
		}

		@Override
		public void onLeft(LeftItem item) {
			if (item.isCancelled()) {
				// If the item was cancelled then we can
				// immediately remove the queue state
				ThrottleManager.get().deleteQueueBlockingState(item.getId());
			} else {
				// Otherwise we must wait until the work
				// unit is aborted (i.e. the future is done)
				// or the run is started
				ThrottleManager.get().setQueueBlockingStateWork(item.getId(), item.outcome);
			}
		}

	}

}
