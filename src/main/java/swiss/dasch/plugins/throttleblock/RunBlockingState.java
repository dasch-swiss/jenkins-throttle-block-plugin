package swiss.dasch.plugins.throttleblock;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

import hudson.model.Run;

public class RunBlockingState implements Serializable {

	private static final long serialVersionUID = -2521496847064159319L;

	private transient Run<?, ?> run;
	private String runId;

	private LinkedHashSet<IThrottleLock> queuedLocks = new LinkedHashSet<>();
	private IThrottleLock enteredLock = null;

	private volatile long unlockTime;

	public RunBlockingState(Run<?, ?> run) {
		this.run = run;
		runId = run.getExternalizableId();
		unlockTime = System.currentTimeMillis();
	}

	public Run<?, ?> getRun() {
		if (run == null && runId != null) {
			run = Run.fromExternalizableId(runId);
		}
		return run;
	}

	private boolean isBlockingByTimeout(long msAfterLastUnlock) {
		return System.currentTimeMillis() - unlockTime < msAfterLastUnlock;
	}

	public synchronized boolean isBlocking(long msAfterLastUnlock) {
		return enteredLock != null || isBlockingByTimeout(msAfterLastUnlock);
	}

	public synchronized boolean isExpired(long msAfterLastUnlock) {
		Run<?, ?> run = getRun();
		return !isBlockingByTimeout(msAfterLastUnlock) && (run == null || !run.isBuilding());
	}

	public boolean tryClearOnExpiration(long msAfterLastUnlock) {
		List<IThrottleLock> locks = null;

		synchronized (this) {
			if (isExpired(msAfterLastUnlock)) {
				locks = new ArrayList<>(queuedLocks);
				if (enteredLock != null) {
					locks.add(enteredLock);
				}
				enteredLock = null;
				queuedLocks.clear();
			}
		}

		if (locks != null) {
			for (IThrottleLock lock : locks) {
				lock.abort();
			}
			return true;
		}

		return false;
	}

	public boolean unlock(IThrottleLock lock) {
		return unlock(l -> l.equals(lock));
	}

	public boolean unlock(Predicate<IThrottleLock> predicate) {
		boolean unlocked = testAndUnlock(predicate);
		if (unlocked) {
			ThrottleManager.get().yield();
		}
		return unlocked;
	}

	private synchronized boolean testAndUnlock(Predicate<IThrottleLock> predicate) {
		if (enteredLock != null && testAndUnlock(enteredLock, predicate)) {
			return true;
		}

		for (IThrottleLock lock : queuedLocks) {
			if (testAndUnlock(lock, predicate)) {
				return true;
			}
		}

		return false;
	}

	private synchronized boolean testAndUnlock(IThrottleLock lock, Predicate<IThrottleLock> predicate) {
		if (predicate.test(lock)) {
			queuedLocks.remove(lock);

			if (enteredLock == lock) {
				enteredLock = null;
			}

			unlockTime = System.currentTimeMillis();

			return true;
		}

		return false;
	}

	synchronized boolean queueLock(IThrottleLock lock) {
		return queuedLocks.add(lock);
	}

	synchronized IThrottleLock tryDequeueLock() {
		if (enteredLock == null) {
			Iterator<IThrottleLock> it = queuedLocks.iterator();

			if (it.hasNext()) {
				IThrottleLock lock = it.next();
				it.remove();
				return enteredLock = lock;
			}
		}
		return null;
	}

}
