package swiss.dasch.plugins.throttleblock;

import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;

public class EnterThrottleStep extends Step implements Serializable {

	private static final long serialVersionUID = 7980213995816818464L;

	@DataBoundConstructor
	public EnterThrottleStep() {
	}

	@Override
	public StepExecution start(StepContext context) throws Exception {
		return new EnterThrottleStepExecution(context);
	}

	@Extension
	@Symbol("enterThrottle")
	public static final class DescriptorImpl extends StepDescriptor {

		@Override
		public String getFunctionName() {
			return "enterThrottle";
		}

		@Override
		public String getDisplayName() {
			return Messages.EnterThrottleStep_DisplayName();
		}

		@Override
		public boolean takesImplicitBlockArgument() {
			return true;
		}

		@Override
		public Set<? extends Class<?>> getRequiredContext() {
			return new HashSet<>(Arrays.asList(Run.class, TaskListener.class, FlowNode.class));
		}

	}

}
