package swiss.dasch.plugins.throttleblock;

import java.io.Serializable;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;

public final class RunThrottle extends AbstractDescribableImpl<RunThrottle> implements Serializable {

	private static final long serialVersionUID = 5793409714309337524L;

	private boolean exitAfterCheckout;

	@DataBoundConstructor
	public RunThrottle() {
	}

	public boolean getExitAfterCheckout() {
		return exitAfterCheckout;
	}

	@DataBoundSetter
	public void setExitAfterCheckout(boolean exitAfterCheckout) {
		this.exitAfterCheckout = exitAfterCheckout;
	}

	@Extension
	public static final class DescriptorImpl extends Descriptor<RunThrottle> {
	}

}