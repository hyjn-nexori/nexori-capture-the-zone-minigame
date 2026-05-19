package io.github.hyjn.nexoridemo.midcapture;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class MidCaptureEventBus {

    private final Map<Class<?>, List<Consumer<?>>> listenersByType = new LinkedHashMap<>();

    @Nonnull
    public <T> MidCaptureListenerRegistration register(
        @Nonnull Class<T> type,
        @Nonnull Consumer<T> listener
    ) {
        if (type == null) {
            throw new IllegalArgumentException("Event type cannot be null.");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Event listener cannot be null.");
        }
        synchronized (listenersByType) {
            listenersByType.computeIfAbsent(type, ignored -> new ArrayList<>()).add(listener);
        }
        return new Registration(type, listener);
    }

    public void publish(@Nonnull Object event) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null.");
        }
        List<Consumer<?>> listeners;
        synchronized (listenersByType) {
            listeners = List.copyOf(listenersByType.getOrDefault(event.getClass(), List.of()));
        }
        for (Consumer<?> listener : listeners) {
            invoke(listener, event);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void invoke(@Nonnull Consumer<?> listener, @Nonnull T event) {
        try {
            ((Consumer<T>) listener).accept(event);
        } catch (RuntimeException exception) {
            // Keep gameplay dispatch resilient; logging can be added when the bus is wired to plugin services.
        }
    }

    private final class Registration implements MidCaptureListenerRegistration {

        private final Class<?> type;
        private final Consumer<?> listener;
        private boolean closed;

        private Registration(@Nonnull Class<?> type, @Nonnull Consumer<?> listener) {
            this.type = type;
            this.listener = listener;
        }

        @Override
        public void close() {
            synchronized (listenersByType) {
                if (closed) {
                    return;
                }
                closed = true;
                List<Consumer<?>> listeners = listenersByType.get(type);
                if (listeners == null) {
                    return;
                }
                listeners.remove(listener);
                if (listeners.isEmpty()) {
                    listenersByType.remove(type);
                }
            }
        }
    }
}
