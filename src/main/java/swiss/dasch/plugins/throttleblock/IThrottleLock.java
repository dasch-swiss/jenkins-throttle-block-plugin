package swiss.dasch.plugins.throttleblock;

import java.io.Serializable;

public interface IThrottleLock extends Serializable {
	void enter();

	void abort();
}
