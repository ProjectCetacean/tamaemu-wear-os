package io.github.projectcetacean.tamaemu;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
/** Deadline lifecycle tests use real executors and blocked workers, without Android/JNI. */
public final class TileCallbackQueueTest {
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    static ThreadPoolExecutor worker(){return new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8));}
    static void stall(ThreadPoolExecutor w,CountDownLatch release)throws Exception {
        CountDownLatch started=new CountDownLatch(1);w.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        check(started.await(1,TimeUnit.SECONDS),"worker started");
    }
    public static void main(String[] args)throws Exception {
        if(args.length==0 || args[0].equals("deadline")) {
            ThreadPoolExecutor w=worker();TileCallbackQueue q=new TileCallbackQueue(w,80);CountDownLatch release=new CountDownLatch(1);
            try {
                stall(w,release);CompletableFuture<String> result=new CompletableFuture<>();AtomicInteger executions=new AtomicInteger();
                q.submit(()->{executions.incrementAndGet();return "wrong newer bytes";},"exact-N empty",result::complete);
                check(result.get(1,TimeUnit.SECONDS).equals("exact-N empty"),"deadline independently resolves callback");
                check(w.getQueue().isEmpty(),"expired queued work removed");release.countDown();w.submit(()->{}).get(1,TimeUnit.SECONDS);
                check(executions.get()==0,"expired queued work never executes");
            }finally{release.countDown();q.close();}
        }
        if(args.length==0 || args[0].equals("teardown")) {
            ThreadPoolExecutor w=worker();TileCallbackQueue q=new TileCallbackQueue(w,5000);CountDownLatch release=new CountDownLatch(1);
            try {
                stall(w,release);CompletableFuture<String> result=new CompletableFuture<>();q.submit(()->"wrong","neutral",result::complete);
                q.close();check(result.get(1,TimeUnit.SECONDS).equals("neutral"),"teardown resolves pending callbacks");check(w.getQueue().isEmpty(),"teardown removes queued callbacks");
                CompletableFuture<String> later=new CompletableFuture<>();q.submit(()->"wrong","closed",later::complete);
                check(later.get(1,TimeUnit.SECONDS).equals("closed"),"closed queue resolves new request immediately");
            }finally{release.countDown();q.close();}
        }
        if(args.length==0) {
            ThreadPoolExecutor w=worker();TileCallbackQueue q=new TileCallbackQueue(w,80);CountDownLatch release=new CountDownLatch(1);AtomicInteger completions=new AtomicInteger();
            try {
                CompletableFuture<String> result=new CompletableFuture<>();CountDownLatch started=new CountDownLatch(1);
                q.submit(()->{started.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}return "late";},"timeout",value->{completions.incrementAndGet();result.complete(value);});
                check(started.await(1,TimeUnit.SECONDS),"active task started");check(result.get(1,TimeUnit.SECONDS).equals("timeout"),"active stall times out");
                release.countDown();w.submit(()->{}).get(1,TimeUnit.SECONDS);check(completions.get()==1,"late completion ignored");
                CountDownLatch blocked=new CountDownLatch(1);stall(w,blocked);
                AtomicInteger cancelled=new AtomicInteger();Runnable cancel=q.submit(()->"unwanted","fallback",v->cancelled.incrementAndGet());cancel.run();
                check(w.getQueue().isEmpty(),"caller cancellation removes work");check(cancelled.get()==0,"caller cancellation does not deliver fallback");blocked.countDown();
            }finally{release.countDown();q.close();}
        }
        System.out.println("TileCallbackQueueTest: "+(args.length==0?"deadline, teardown, cancellation and late completion":args[0])+" passed");
    }
}
