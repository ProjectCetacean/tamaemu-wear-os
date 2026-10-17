package io.github.projectcetacean.tamaemu;

import java.util.ArrayList;
import java.util.List;

public final class LifeSchedulerTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        final int[] scheduled={0},cancelled={0};final boolean[] okay={true};final List<String> statuses=new ArrayList<>();
        LifeScheduler scheduler=new LifeScheduler(new LifeScheduler.Port(){
            public boolean schedule(){scheduled[0]++;return okay[0];}
            public void cancel(){cancelled[0]++;}
        },(text,failed)->statuses.add((failed?"failure:":"ok:")+text));
        scheduler.reconcile(LifeRecord.from(new long[8]));check(scheduled[0]==0&&cancelled[0]==1,"no schedule before first aging checkpoint");
        scheduler.reconcile(LifeRecord.from(new long[]{1,100,0,1,0,0,1,0}));check(scheduled[0]==1,"Normal scheduled");
        scheduler.reconcile(LifeRecord.from(new long[]{1,100,0,1,0,1,2,0}));check(cancelled[0]==2,"settled Protected cancels");
        scheduler.reconcile(LifeRecord.from(new long[]{1,100,0,1,1,1,3,0}));check(scheduled[0]==2,"Protected owed debt still scheduled");
        okay[0]=false;scheduler.reconcile(LifeRecord.from(new long[]{1,100,0,1,0,0,4,0}));check(statuses.get(statuses.size()-1).startsWith("failure:"),"schedule failure exposed");
        final List<JobLease> leases=new ArrayList<>();final List<JobCompletion> completions=new ArrayList<>();final List<String> finished=new ArrayList<>();
        LifeJobs<String> jobs=new LifeJobs<>(new LifeJobs.Runner(){
            public void run(JobLease lease,JobCompletion completion){leases.add(lease);completions.add(completion);}
            public void cancel(JobLease lease){check(lease.isCancelled(),"stop invalidates synchronously before worker cancellation");}
        },finished::add);
        jobs.start("one");jobs.stop("one");completions.get(0).complete(leases.get(0));check(finished.isEmpty(),"stopped job cannot finish late");
        jobs.start("two");completions.get(1).complete(leases.get(1));completions.get(1).complete(leases.get(1));check(finished.size()==1,"exactly one completion");
        jobs.start("same");jobs.start("same");completions.get(2).complete(leases.get(2));check(finished.size()==1,"stale same-parameter lease cannot remove newer job");
        completions.get(3).complete(leases.get(3));check(finished.size()==2,"newest lease completes");
        jobs.start("destroy");jobs.close();completions.get(4).complete(leases.get(4));check(finished.size()==2,"destroy cancels without late finish");
        System.out.println("LifeSchedulerTest: "+checks+" checks passed");
    }
}
