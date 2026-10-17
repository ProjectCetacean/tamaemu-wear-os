package io.github.projectcetacean.tamaemu;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Independent callback deadlines: a blocked cache worker cannot block completion or cleanup. */
final class TileCallbackQueue {
    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Set<Pending<?>> pending=ConcurrentHashMap.newKeySet();
    private final long timeoutMillis;
    private final Object lifecycle=new Object();
    private boolean closed;

    TileCallbackQueue(ThreadPoolExecutor worker,long timeoutMillis) {
        if(timeoutMillis<=0)throw new IllegalArgumentException("Positive callback deadline required");
        this.worker=worker;this.timeoutMillis=timeoutMillis;
        deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"pet-tile-deadlines");t.setDaemon(true);return t;});
        deadlines.setRemoveOnCancelPolicy(true);
    }
    <T> Runnable submit(Callable<T> work,T fallback,Consumer<T> complete) {
        Pending<T> task=new Pending<>(work,fallback,complete);
        synchronized(lifecycle) {
            if(closed){task.finish(fallback,true);return ()->{};}
            pending.add(task);
            task.timeout=deadlines.schedule(()->task.finish(fallback,true),timeoutMillis,TimeUnit.MILLISECONDS);
            if(task.finished.get())task.timeout.cancel(false);
            try{worker.execute(task);if(task.finished.get())worker.remove(task);}catch(RejectedExecutionException e){task.finish(fallback,true);}
        }
        return ()->task.finish(fallback,false);
    }
    private final class Pending<T> extends FutureTask<T> {
        private final T fallback;
        private final Consumer<T> complete;
        private final AtomicBoolean finished=new AtomicBoolean();
        private volatile ScheduledFuture<?> timeout;
        Pending(Callable<T> work,T fallback,Consumer<T> complete){super(work);this.fallback=fallback;this.complete=complete;}
        void finish(T value,boolean deliver) {
            if(!finished.compareAndSet(false,true))return;
            ScheduledFuture<?> timer=timeout;if(timer!=null)timer.cancel(false);
            pending.remove(this);worker.remove(this);
            // Cancellation suppresses late results and interrupts only this task if already running.
            if(!isDone())cancel(true);
            if(deliver)complete.accept(value);
        }
        void fallback(){finish(fallback,true);}
        @Override protected void done() {
            if(finished.get())return;
            try{finish(get(),true);}catch(InterruptedException e){Thread.currentThread().interrupt();finish(fallback,true);}
            catch(ExecutionException|CancellationException e){finish(fallback,true);}
        }
    }
    void close() {
        synchronized(lifecycle){if(closed)return;closed=true;}
        // Resolve before shutdownNow can discard queued tasks or stop their timers.
        for(Pending<?> task:pending)task.fallback();
        worker.shutdownNow();deadlines.shutdownNow();
    }
}
