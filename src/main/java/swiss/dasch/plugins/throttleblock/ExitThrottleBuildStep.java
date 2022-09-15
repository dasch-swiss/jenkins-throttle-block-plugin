package swiss.dasch.plugins.throttleblock;

import java.io.IOException;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;

public class ExitThrottleBuildStep extends Builder {

	@DataBoundConstructor
	public ExitThrottleBuildStep() {
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
			throws InterruptedException, IOException {

		// Create an unlocked blocking state so that
		// that this run no longer throttles other runs
		if (ThrottleManager.get().createRunBlockingState(build)) {
			listener.getLogger().println("Run will no longer throttle other runs");
		}

		return true;
	}

	@Symbol("exitThrottle")
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		@Override
		public String getDisplayName() {
			return Messages.ExitThrottleBuildStep_DisplayName();
		}

		@SuppressWarnings("rawtypes")
		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

	}

}
