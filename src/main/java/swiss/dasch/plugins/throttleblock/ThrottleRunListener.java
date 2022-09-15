package swiss.dasch.plugins.throttleblock;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

@Extension
public class ThrottleRunListener extends RunListener<Run<?, ?>> {

	private void deleteRunBlockingStateIfExpired(Run<?, ?> run) {
		ThrottleManager manager = ThrottleManager.get();
		manager.deleteRunBlockingState(run, state -> state.tryClearOnExpiration(manager.getTimeoutAfterUnlock()));
	}

	@Override
	public void onCompleted(Run<?, ?> run, TaskListener listener) {
		deleteRunBlockingStateIfExpired(run);
	}

	@Override
	public void onFinalized(Run<?, ?> run) {
		deleteRunBlockingStateIfExpired(run);
	}

	@Override
	public void onDeleted(Run<?, ?> run) {
		deleteRunBlockingStateIfExpired(run);
	}

}