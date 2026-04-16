package android.hardware;

/**
 * Compile-time stub for BYD camera service interface.
 *
 * At runtime, the real implementation comes from bmmcamera.jar on the device.
 * This stub exists only so the code compiles.
 *
 * The BYD framework uses IBYDCameraService.Stub.asInterface() (ZERO-ARG)
 * which internally gets the binder from ServiceManager.
 *
 * Method signatures and transaction codes verified via runtime discovery on BYD Seal.
 */
public interface IBYDCameraService extends android.os.IInterface {

    boolean preOpenCamera(IBYDCameraUser user) throws android.os.RemoteException;   // TRANSACTION 1
    void openCamera(IBYDCameraUser user) throws android.os.RemoteException;         // TRANSACTION 2
    boolean posCloseCamera(IBYDCameraUser user) throws android.os.RemoteException;  // TRANSACTION 3
    boolean registerUser(IBYDCameraUser user) throws android.os.RemoteException;    // TRANSACTION 4
    boolean unregisterUser(IBYDCameraUser user) throws android.os.RemoteException;  // TRANSACTION 5
    IBYDCameraUser getCurrentCameraUser() throws android.os.RemoteException;        // TRANSACTION 6
    void onError(IBYDCameraUser user) throws android.os.RemoteException;            // TRANSACTION 7

    abstract class Stub extends android.os.Binder implements IBYDCameraService {

        private static final String DESCRIPTOR = "android.hardware.IBYDCameraService";

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        /**
         * BYD-custom zero-arg asInterface — gets binder from ServiceManager internally.
         * At compile time returns null. At runtime, bmmcamera.jar provides the real implementation.
         */
        public static IBYDCameraService asInterface() {
            return null;
        }

        /**
         * Standard AIDL asInterface with IBinder parameter.
         */
        public static IBYDCameraService asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IBYDCameraService) {
                return (IBYDCameraService) iin;
            }
            return new Proxy(obj);
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        private static class Proxy implements IBYDCameraService {
            private final android.os.IBinder mRemote;

            Proxy(android.os.IBinder remote) {
                mRemote = remote;
            }

            @Override
            public android.os.IBinder asBinder() {
                return mRemote;
            }

            @Override
            public boolean preOpenCamera(IBYDCameraUser user) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(user != null ? user.asBinder() : null);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 0, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void openCamera(IBYDCameraUser user) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(user != null ? user.asBinder() : null);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 1, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean posCloseCamera(IBYDCameraUser user) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(user != null ? user.asBinder() : null);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 2, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean registerUser(IBYDCameraUser user) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(user != null ? user.asBinder() : null);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 3, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean unregisterUser(IBYDCameraUser user) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(user != null ? user.asBinder() : null);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 4, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public IBYDCameraUser getCurrentCameraUser() throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 5, data, reply, 0);
                    reply.readException();
                    android.os.IBinder binder = reply.readStrongBinder();
                    return IBYDCameraUser.Stub.asInterface(binder);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void onError(IBYDCameraUser user) throws android.os.RemoteException {
                android.os.Parcel data = android.os.Parcel.obtain();
                android.os.Parcel reply = android.os.Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(user != null ? user.asBinder() : null);
                    mRemote.transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 6, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
