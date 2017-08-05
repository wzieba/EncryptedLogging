package com.automattic.encryptedlogging.store;

import com.automattic.encryptedlogging.Dispatcher;
import com.automattic.encryptedlogging.FluxCError;
import com.automattic.encryptedlogging.annotations.action.Action;

public abstract class Store {
    protected final Dispatcher mDispatcher;

    Store(Dispatcher dispatcher) {
        mDispatcher = dispatcher;
        mDispatcher.register(this);
    }

    public interface OnChangedError extends FluxCError {}

    public static class OnChanged<T extends OnChangedError> {
        public T error = null;

        public boolean isError() {
            return error != null;
        }
    }

    /**
     * onAction should {@link org.greenrobot.eventbus.Subscribe} with ASYNC {@link org.greenrobot.eventbus.ThreadMode}.
     */
    public abstract void onAction(Action action);
    public abstract void onRegister();

    protected void emitChange(OnChanged onChangedEvent) {
        mDispatcher.emitChange(onChangedEvent);
    }
}
