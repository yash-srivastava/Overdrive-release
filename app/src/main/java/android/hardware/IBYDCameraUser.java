package android.hardware;

/**
 * Compile-time stub for BYD camera user interface.
 *
 * At runtime, the real implementation comes from bmmcamera.jar on the device.
 * This stub exists only so the code compiles.
 *
 * Method signatures and transaction codes verified via runtime discovery on BYD Seal.
 */
public interface IBYDCameraUser extends android.os.IInterface {

    boolean onPreOpenCamera(IBYDCameraUser requester, int cameraId) throws android.os.RemoteException;  // TRANSACTION 1
    boolean onOpenCamera(IBYDCameraUser requester, int cameraId) throws android.os.RemoteException;     // TRANSACTION 2
    boolean onCloseCamera(IBYDCameraUser requester, int cameraId) throws android.os.RemoteException;    // TRANSACTION 3
    String getPackageName() throws android.os.RemoteException;                                           // TRANSACTION 4
    int getCameraId() throws android.os.RemoteException;                                                 // TRANSACTION 5
    String getProperty(String key) throws android.os.RemoteException;                                    // TRANSACTION 6
    boolean onError(String error, int code) throws android.os.RemoteException;                           // TRANSACTION 7

    abstract class Stub extends android.os.Binder implements IBYDCameraUser {

        private static final String DESCRIPTOR = "android.hardware.IBYDCameraUser";

        // Transaction codes verified from runtime discovery
        static final int TRANSACTION_onPreOpenCamera = 1;
        static final int TRANSACTION_onOpenCamera = 2;
        static final int TRANSACTION_onCloseCamera = 3;
        static final int TRANSACTION_getPackageName = 4;
        static final int TRANSACTION_getCameraId = 5;
        static final int TRANSACTION_getProperty = 6;
        static final int TRANSACTION_onError = 7;

        public Stub() {
            this.attachInterface(this, DESCRIPTOR);
        }

        public static IBYDCameraUser asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IBYDCameraUser) {
                return (IBYDCameraUser) iin;
            }
            return null;
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags)
                throws android.os.RemoteException {
            switch (code) {
                case TRANSACTION_onPreOpenCamera: {
                    data.enforceInterface(DESCRIPTOR);
                    IBYDCameraUser requester = IBYDCameraUser.Stub.asInterface(data.readStrongBinder());
                    int cameraId = data.readInt();
                    boolean result = this.onPreOpenCamera(requester, cameraId);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_onOpenCamera: {
                    data.enforceInterface(DESCRIPTOR);
                    IBYDCameraUser requester = IBYDCameraUser.Stub.asInterface(data.readStrongBinder());
                    int cameraId = data.readInt();
                    boolean result = this.onOpenCamera(requester, cameraId);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_onCloseCamera: {
                    data.enforceInterface(DESCRIPTOR);
                    IBYDCameraUser requester = IBYDCameraUser.Stub.asInterface(data.readStrongBinder());
                    int cameraId = data.readInt();
                    boolean result = this.onCloseCamera(requester, cameraId);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
                case TRANSACTION_getPackageName: {
                    data.enforceInterface(DESCRIPTOR);
                    String result = this.getPackageName();
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_getCameraId: {
                    data.enforceInterface(DESCRIPTOR);
                    int result = this.getCameraId();
                    reply.writeNoException();
                    reply.writeInt(result);
                    return true;
                }
                case TRANSACTION_getProperty: {
                    data.enforceInterface(DESCRIPTOR);
                    String key = data.readString();
                    String result = this.getProperty(key);
                    reply.writeNoException();
                    reply.writeString(result);
                    return true;
                }
                case TRANSACTION_onError: {
                    data.enforceInterface(DESCRIPTOR);
                    String error = data.readString();
                    int errorCode = data.readInt();
                    boolean result = this.onError(error, errorCode);
                    reply.writeNoException();
                    reply.writeInt(result ? 1 : 0);
                    return true;
                }
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
