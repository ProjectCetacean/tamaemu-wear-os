package io.github.projectcetacean.tamaemu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Reconciles only authoritative committed records; execution timing remains opportunistic. */
final class LifeScheduler {
    interface Port { boolean schedule(); void cancel(); }
    interface Status { void update(String text,boolean failed); }
    private final Port port;private final Status status;
    LifeScheduler(Port port,Status status){this.port=port;this.status=status;}
    void reconcile(LifeRecord record){
        try {
            if(record.schema==0||(record.mode==1&&record.debtMs==0)) {
                port.cancel();status.update(record.schema==0?"Aging not initialized":"Paused away",false);
            } else {
                boolean okay=port.schedule();status.update(okay?"Periodic aging scheduled":"Background scheduling unavailable",!okay);
            }
        }catch(RuntimeException failure){status.update("Background scheduling unavailable",true);}
    }
}

/** Main-thread job parameter/lease registry. Worker completion never revives a stopped lease. */
final class LifeJobs<P> {
    interface Runner {void run(JobLease lease,JobCompletion completion);void cancel(JobLease lease);}
    interface Finished<P> {void finish(P parameters);}
    private final Runner runner;private final Finished<P> finished;
    private final Map<P,JobLease> active=new IdentityHashMap<>();
    LifeJobs(Runner runner,Finished<P> finished){this.runner=runner;this.finished=finished;}
    void start(P parameters){
        stop(parameters);JobLease lease=new JobLease();active.put(parameters,lease);
        try{runner.run(lease,completed->{
            if(active.get(parameters)!=completed||completed.isCancelled())return;
            active.remove(parameters);finished.finish(parameters);
        });}catch(RuntimeException failure){stop(parameters);throw failure;}
    }
    void stop(P parameters){
        JobLease lease=active.remove(parameters);if(lease!=null){lease.cancel();runner.cancel(lease);}
    }
    void close(){for(P parameters:new ArrayList<>(active.keySet()))stop(parameters);}
}
