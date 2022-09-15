package swiss.dasch.plugins.throttleblock;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import hudson.BulkChange;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import net.sf.json.JSONObject;

public class ThrottleJobProperty extends JobProperty<Job<?, ?>> {

	private RunThrottle runThrottle;

	@DataBoundConstructor
	public ThrottleJobProperty(RunThrottle runThrottle) {
		this.runThrottle = runThrottle;
	}

	public RunThrottle getRunThrottle() {
		return runThrottle;
	}

	@Symbol("throttleBlock")
	@Extension
	public static final class DescriptorImpl extends JobPropertyDescriptor {

		private static final Logger LOGGER = Logger.getLogger(DescriptorImpl.class.getName());

		private RunThrottle runThrottle;

		@DataBoundConstructor
		public DescriptorImpl() {
			resetProperties();
			load();
		}

		private void resetProperties() {
			runThrottle = null;
		}

		public RunThrottle getRunThrottle() {
			return runThrottle;
		}

		@DataBoundSetter
		public void setRunThrottle(RunThrottle runThrottle) {
			this.runThrottle = runThrottle;
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
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

		@Override
		public String getDisplayName() {
			return Messages.ThrottleJobProperty_DisplayName();
		}

	}
}
