package swiss.dasch.plugins.throttleblock;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;

import hudson.model.Run;
import hudson.model.TaskListener;

public class ExitThrottleStepExecution extends AbstractStepExecutionImpl implements Serializable {

	private static final long serialVersionUID = 8264603482798451978L;

	public ExitThrottleStepExecution(StepContext context) {
		super(context);
	}

	@Override
	public boolean start() throws Exception {
		StepContext context = getContext();
		Run<?, ?> run = context.get(Run.class);

		// Create an unlocked blocking state so that
		// that this run no longer throttles other runs
		if (ThrottleManager.get().createRunBlockingState(run)) {
			context.get(TaskListener.class).getLogger().println("Run will no longer throttle other runs");
		}

		return false;
	}

}
