package swiss.dasch.plugins.throttleblock;

import java.io.File;

import hudson.Extension;
import hudson.FilePath;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.SCMListener;
import hudson.scm.SCM;
import hudson.scm.SCMRevisionState;

@Extension
public class ThrottleSCMListener extends SCMListener {

	@Override
	public void onCheckout(Run<?, ?> run, SCM scm, FilePath workspace, TaskListener listener, File changelogFile,
			SCMRevisionState pollingBaseline) throws Exception {
		if (run instanceof Queue.Executable) {
			ThrottleJobProperty property = run.getParent().getProperty(ThrottleJobProperty.class);

			if (property != null) {
				RunThrottle runThrottle = property.getRunThrottle();

				if (runThrottle != null && runThrottle.getExitAfterCheckout()) {

					// Create an unlocked blocking state to let other jobs run
					// once the checkout delay has run out (running jobs without
					// any blocking state are considered to be blocking because
					// they haven't checked out yet)
					if (ThrottleManager.get().createRunBlockingState(run)) {
						listener.getLogger().println("Run has checked out and will no longer throttle other runs");
					}

				}
			}
		}
	}

}