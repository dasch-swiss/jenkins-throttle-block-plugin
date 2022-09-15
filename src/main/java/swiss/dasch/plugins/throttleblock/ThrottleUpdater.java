package swiss.dasch.plugins.throttleblock;

import hudson.Extension;
import hudson.model.PeriodicWork;

@Extension
public class ThrottleUpdater extends PeriodicWork {

	@Override
	public long getRecurrencePeriod() {
		return 1000;
	}

	@Override
	protected void doRun() throws Exception {
		ThrottleManager.get().maintain();
	}

}