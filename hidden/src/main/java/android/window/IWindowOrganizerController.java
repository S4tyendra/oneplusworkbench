package android.window;

import android.os.IInterface;
import android.os.RemoteException;

public interface IWindowOrganizerController extends IInterface {
    void applyTransaction(WindowContainerTransaction t) throws RemoteException;
}
