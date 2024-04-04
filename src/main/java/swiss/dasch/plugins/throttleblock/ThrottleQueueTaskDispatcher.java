package swiss.dasch.plugins.throttleblock;

import hudson.Extension;
import hudson.model.Queue.Item;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import jenkins.model.Jenkins;
import swiss.dasch.plugins.throttleblock.ThrottleManager.QueueResult;

@Extension
public class ThrottleQueueTaskDispatcher extends QueueTaskDispatcher {

	@Override
	public CauseOfBlockage canRun(Item item) {
		QueueResult result = ThrottleManager.get().tryQueueItem(Jenkins.get(), item, true);

		switch (result) {
		default:
		case OK:
			return null;
		case BLOCKED_BY_QUEUED_ITEM:
			return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BlockedByQueuedItem_Message());
		case BLOCKED_BY_WORK_UNIT:
			return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BlockedByWorkUnit_Message());
		case BLOCKED_BY_BLOCKING_STATE:
			return CauseOfBlockage.fromMessage(Messages._ThrottleQueueTaskDispatcher_BlockedByBlockingState_Message());
		case BLOCKED_BY_NO_BLOCKING_STATE:
			return CauseOfBlockage
					.fromMessage(Messages._ThrottleQueueTaskDispatcher_BlockedByNoBlockingState_Message());
		}
	}

}
