package android.app;

import android.content.ComponentName;
import android.os.RemoteException;

public abstract class TaskStackListener extends ITaskStackListener.Stub {
    public TaskStackListener() { }
    public void onTaskStackChanged() throws RemoteException { }
    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException { }
    public void onTaskRemoved(int taskId) throws RemoteException { }
    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) throws RemoteException { }
    public void onTaskMovedToBack(ActivityManager.RunningTaskInfo taskInfo) throws RemoteException { }
    public void onTaskFocusChanged(int taskId, boolean focused) throws RemoteException { }
}
