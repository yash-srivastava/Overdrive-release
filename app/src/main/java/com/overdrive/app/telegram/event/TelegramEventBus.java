package com.overdrive.app.telegram.event;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thread-safe event bus implementation.
 * Singleton pattern for global access.
 */
public class TelegramEventBus implements ITelegramEventBus {
    
    private static volatile TelegramEventBus instance;
    
    private final List<EventListener> globalListeners = new CopyOnWriteArrayList<>();
    private final Map<SystemEvent.EventType, List<EventListener>> typedListeners = new EnumMap<>(SystemEvent.EventType.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TelegramEventBus");
        t.setDaemon(true);
        return t;
    });
    
    private TelegramEventBus() {
        for (SystemEvent.EventType type : SystemEvent.EventType.values()) {
            typedListeners.put(type, new CopyOnWriteArrayList<>());
        }
    }
    
    public static TelegramEventBus getInstance() {
        if (instance == null) {
            synchronized (TelegramEventBus.class) {
                if (instance == null) {
                    instance = new TelegramEventBus();
                }
            }
        }
        return instance;
    }
    
    @Override
    public void subscribe(EventListener listener) {
        if (listener != null && !globalListeners.contains(listener)) {
            globalListeners.add(listener);
        }
    }
    
    @Override
    public void subscribe(SystemEvent.EventType type, EventListener listener) {
        if (listener != null && type != null) {
            List<EventListener> listeners = typedListeners.get(type);
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }
    
    @Override
    public void unsubscribe(EventListener listener) {
        globalListeners.remove(listener);
        for (List<EventListener> listeners : typedListeners.values()) {
            listeners.remove(listener);
        }
    }
    
    @Override
    public void publish(SystemEvent event) {
        if (event == null) return;
        
        try {
            executor.execute(() -> {
                // Notify global listeners
                for (EventListener listener : globalListeners) {
                    try {
                        listener.onEvent(event);
                    } catch (Exception e) {
                        // Log but don't crash
                        System.err.println("EventBus: listener error: " + e.getMessage());
                    }
                }
                
                // Notify typed listeners
                List<EventListener> typed = typedListeners.get(event.getType());
                if (typed != null) {
                    for (EventListener listener : typed) {
                        try {
                            listener.onEvent(event);
                        } catch (Exception e) {
                            System.err.println("EventBus: typed listener error: " + e.getMessage());
                    }
                }
            }
        });
        } catch (Throwable e) {
            // Catches RejectedExecutionException and "Thread starting during runtime shutdown"
            // which happens when publish() is called during process exit
            System.err.println("EventBus: publish failed (shutdown?): " + e.getMessage());
        }
    }
}
