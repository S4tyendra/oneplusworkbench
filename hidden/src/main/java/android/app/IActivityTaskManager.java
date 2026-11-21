package android.app;

import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.window.IWindowOrganizerController;
import java.util.List;

public interface IActivityTaskManager extends IInterface {
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId) throws RemoteException;
    ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags, int userId) throws RemoteException;
    int startActivityFromRecents(int taskId, Bundle options) throws RemoteException;
    boolean removeTask(int taskId) throws RemoteException;
    void setFocusedTask(int taskId) throws RemoteException;
    void registerTaskStackListener(ITaskStackListener listener) throws RemoteException;
    void unregisterTaskStackListener(ITaskStackListener listener) throws RemoteException;
    IWindowOrganizerController getWindowOrganizerController() throws RemoteException;

    abstract class Stub extends Binder implements IActivityTaskManager {
        public static IActivityTaskManager asInterface(IBinder b) { throw new RuntimeException("Stub!"); }
    }
}
