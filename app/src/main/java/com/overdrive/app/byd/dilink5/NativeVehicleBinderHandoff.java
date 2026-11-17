package com.overdrive.app.byd.dilink5;

/** Orders the durable request channel before optional ServiceManager publication. */
public final class NativeVehicleBinderHandoff {
    public interface RequestListener {
        boolean onRequest(Object callback);
    }

    public interface RequestChannel {
        boolean register(RequestListener listener);
    }

    public interface ServiceRegistry {
        boolean addService();
    }

    public interface Publisher {
        boolean publish(Object callback);
    }

    private final RequestChannel requestChannel;
    private final ServiceRegistry serviceRegistry;
    private final Publisher publisher;
    private boolean requestChannelReady;
    private boolean serviceRegistered;

    public NativeVehicleBinderHandoff(RequestChannel requestChannel,
            ServiceRegistry serviceRegistry, Publisher publisher) {
        this.requestChannel = requestChannel;
        this.serviceRegistry = serviceRegistry;
        this.publisher = publisher;
    }

    /** The late-client path is armed first and remains available if addService is rejected. */
    public synchronized boolean start() {
        if (!requestChannelReady) {
            requestChannelReady = requestChannel != null
                    && requestChannel.register(this::publishRequested);
        }
        if (!serviceRegistered) {
            serviceRegistered = serviceRegistry != null && serviceRegistry.addService();
        }
        return requestChannelReady || serviceRegistered;
    }

    public synchronized boolean requestChannelReady() {
        return requestChannelReady;
    }

    public synchronized boolean serviceRegistered() {
        return serviceRegistered;
    }

    private boolean publishRequested(Object callback) {
        return callback != null && publisher != null && publisher.publish(callback);
    }
}
