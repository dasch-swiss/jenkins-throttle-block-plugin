package swiss.dasch.plugins.throttleblock;

import java.io.Serializable;

import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.PauseAction;

import hudson.model.Run;
import hudson.model.TaskListener;

public class EnterThrottleStepExecution extends AbstractStepExecutionImpl implements Serializable {

	private static final long serialVersionUID = 4805749113912468268L;

	public EnterThrottleStepExecution(StepContext context) {
		super(context);
	}

	private static class Callback extends BodyExecutionCallback.TailCall {

		private static final long serialVersionUID = 495510536171463114L;

		private final StepContext lockStepContext;

		public Callback(StepContext lockStepContext) {
			this.lockStepContext = lockStepContext;
		}

		@Override
		protected void finished(StepContext context) throws Exception {
			Run<?, ?> run = context.get(Run.class);

			context.get(TaskListener.class).getLogger().println("Exiting throttling block");

			RunBlockingState state = ThrottleManager.get().getRunBlockingState(run);

			// Unlock blocking state again to let other locks
			// or jobs execute
			if (state != null && !state.unlock(l -> l instanceof Lock && ((Lock) l).context.equals(lockStepContext))) {
				throw new IllegalStateException("Could not find matching lock for step context");
			}
		}

	}

	private static class Lock implements IThrottleLock, Serializable {

		private static final long serialVersionUID = -4223384494588079564L;

		private StepContext context;

		public Lock(StepContext context) {
			this.context = context;
		}

		@Override
		public void enter() {
			try {
				context.get(TaskListener.class).getLogger().println("Entered throttling block");

				// Body is going to execute so the node is no
				// longer paused
				PauseAction.endCurrentPause(context.get(FlowNode.class));
			} catch (Exception ex) {
				// Not that critical
			}

			// Run step body and unlock blocking state once
			// the body has finished executing
			context.newBodyInvoker().withCallback(new Callback(context)).start();
		}

		@Override
		public void abort() {
			context.onFailure(new Exception("Lock was aborted"));
		}

	}

	@Override
	public boolean start() throws Exception {
		StepContext context = getContext();
		Run<?, ?> run = context.get(Run.class);

		context.get(TaskListener.class).getLogger().println("Reached throttling block");

		// Add pause action so it is shown as paused in the UI
		context.get(FlowNode.class).addAction(new PauseAction("Throttle Block"));

		// Create a blocking state and lock it with a lock
		// until the lock has entered and unlocked itself
		ThrottleManager.get().createRunBlockingState(run, new Lock(context));

		// Lock will be entered and continue execution as
		// soon as another job or lock yields

		return false;
	}

}
