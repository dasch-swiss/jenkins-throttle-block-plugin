package swiss.dasch.plugins.throttleblock;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.Symbol;
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
		return tryQueue(node, item, null);
	}

	public QueueResult tryQueueLock(Node node, RunBlockingState state) {
		return tryQueue(node, null, state);
	}

	private QueueResult tryQueue(Node node, Queue.Item item, RunBlockingState state) {
		Run<?, ?> run = null;

		QueueBlockingState queueState = null;
		boolean wasQueued = false;
		boolean isQueued = false;

		IThrottleLock dequeuedLock = null;

		try {
			synchronized (this) {
				run = state != null ? state.getRun() : null;
				queueState = item == null ? null : queueBlockingStates.get(item.getId());

				// Reset queued state to false because the item
				// has gone backwards in the queue cycle
				if (queueState != null) {
					wasQueued = queueState.isQueued;
					queueState.isQueued = isQueued = false;
				}

				// If there are any other items queued then we
				// must return false because those queued items
				// may start being executed outside of our control
				for (long id : queueBlockingStates.keySet()) {
					if ((item == null || id != item.getId()) && (run == null || id != run.getQueueId())
							&& queueBlockingStates.get(id).isBlocking()) {
						return QueueResult.BLOCKED_BY_QUEUED_ITEM;
					}
				}

				// Check if there are any runs that are
				// actually blocking
				for (RunBlockingState otherState : runBlockingStates) {
					if (otherState.isBlocking(getTimeoutAfterUnlock())) {
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

				// If all checks are passed then this item or
				// lock enter may be queued/started
				if (queueState != null) {
					queueState.isQueued = isQueued = true;
				}

				// If state is specified then try to dequeue a
				// lock
				if (state != null) {
					dequeuedLock = state.tryDequeueLock();
					if (dequeuedLock == null) {
						return QueueResult.NO_LOCK_ENTERED;
					}
				}

				return QueueResult.OK;
			}
		} finally {
			// If a lock was dequeued then it must also
			// be entered
			if (dequeuedLock != null) {
				dequeuedLock.enter();
			}

			// If an item goes from queued to non queued then
			// this may allow other blocking state locks to enter
			// -> yield
			if (wasQueued && !isQueued) {
				yield();
			}
		}
	}

	public boolean createRunBlockingState(Run<?, ?> run) {
		return createRunBlockingState(run, null);
	}

	public boolean createRunBlockingState(Run<?, ?> run, IThrottleLock lock) {
		RunBlockingState state = null;
		boolean isNewState = false;

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
			}

			if (lock != null) {
				state.queueLock(lock);
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
		}

		if (removed) {
			yield();
		}
	}

	private synchronized void setQueueBlockingStateWork(long id, WorkUnitContext work) {
		QueueBlockingState state = queueBlockingStates.get(id);
		if (state != null) {
			state.work = work;
		}
	}

	public void maintain() {
		Queue queue = Jenkins.get().getQueue();

		Queue.withLock(() -> {
			synchronized (this) {
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
						}
					}
				}
			}
		});

		synchronized (this) {
			Iterator<RunBlockingState> it = runBlockingStates.iterator();
			while (it.hasNext()) {
				RunBlockingState state = it.next();
				if (state.tryClearOnExpiration(getTimeoutAfterUnlock())) {
					it.remove();
				}
			}
		}

		ThrottleManager.get().yield();
	}

	public boolean isThrottlingBeforeRun(Job<?, ?> job) {
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

		private volatile boolean isQueued;
		private transient WorkUnitContext work;

		private boolean isBlocking() {
			return isQueued && !isExpired();
		}

		private boolean isExpired() {
			if (work != null) {
				// If the job isn't configured to enter the
				// throttle before running then this queue
				// state isn't needed at all
				if (work.task instanceof Job<?, ?>
						&& !ThrottleManager.get().isThrottlingBeforeRun((Job<?, ?>) work.task)) {
					return true;
				}

				// If the future is done then the run has
				// already exited, so the queue state is
				// certainly not needed anymore...
				if (work.future.isDone()) {
					return true;
				}

				// If there already is an executable and it
				// already has a blocking state then we don't
				// need the queue state anymore
				Executable executable = work.getPrimaryWorkUnit().getExecutable();
				if (executable != null && (executable instanceof Run<?, ?> == false
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
			if (item.task instanceof Job<?, ?> && !ThrottleManager.get().isThrottlingBeforeRun((Job<?, ?>) item.task)) {
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
