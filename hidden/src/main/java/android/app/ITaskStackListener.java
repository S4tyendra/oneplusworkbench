package android.app;

import android.os.Binder;
import android.os.IInterface;

public interface ITaskStackListener extends IInterface {
    abstract class Stub extends Binder implements ITaskStackListener {
        public android.os.IBinder asBinder() { return this; }
    }
}
