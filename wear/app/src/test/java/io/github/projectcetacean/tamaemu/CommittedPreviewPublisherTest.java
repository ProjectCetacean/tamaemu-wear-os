package io.github.projectcetacean.tamaemu;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Android-free tests of actual commit metadata, bounded dispatch and failure recovery. */
public final class CommittedPreviewPublisherTest {
    private static int checks;
    private static synchronized void check(boolean condition,String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> work=new ArrayDeque<>();
        int submissions;
        boolean reject;
        public synchronized void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("unavailable");
            submissions++; work.add(command);
        }
        synchronized int size() { return work.size(); }
        void runNext() {
            Runnable command;
            synchronized(this) { command=work.remove(); }
            command.run();
        }
    }
    private static CommitSnapshot snapshot(long generation,long mode) {
        byte[] hash=new byte[32]; Arrays.fill(hash,(byte)0x35);
        int[] frame=new int[16384]; Arrays.fill(frame,0xff123456);
        return new CommitSnapshot(LifeRecord.from(new long[]{1,1700000000000L,1200,4,90000,mode,generation,1}),
                hash,frame,1800000000999L);
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5,TimeUnit.SECONDS),"deterministic worker handoff completed");
    }
    private static void join(Thread thread) throws InterruptedException {
        thread.join(5000);
        check(!thread.isAlive(),"worker terminated without stranding work");
    }
    private static void exactMetadataAndImmutableInput() {
        QueueExecutor executor=new QueueExecutor();
        List<PetPreviewSnapshot> writes=new ArrayList<>();
        int[] refreshes={0};
        byte[] hash=new byte[32]; Arrays.fill(hash,(byte)0x35);
        int[] frame=new int[16384]; Arrays.fill(frame,0xff123456);
        long[] slots={1,1700000000000L,1200,4,90000,1,7,1};
        CommitSnapshot commit=new CommitSnapshot(LifeRecord.from(slots),hash,frame,1800000000999L);
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,writes::add,()->refreshes[0]++);
        publisher.committed(commit);
        check(writes.isEmpty() && refreshes[0]==0,"submission performs no synchronous cache I/O or refresh");
        Arrays.fill(hash,(byte)0); Arrays.fill(frame,0); slots[6]=999;
        commit.payloadHash()[0]=0; commit.rawFrame()[0]=0;
        executor.runNext();
        check(writes.size()==1,"one actual commit published");
        PetPreviewSnapshot result=writes.get(0);
        check(result.committedUtcMillis==1800000000999L,"Updated is exact actual commit UTC, independent of accounting anchor/debt");
        check(result.generation==7 && result.debtMillis==90000 && result.clockQuality==1,"literal committed generation, debt and quality preserved");
        check(result.mode==PetPreviewSnapshot.Mode.PROTECTED && result.awayPaused,"committed Protected means paused away");
        byte[] expectedHash=new byte[32]; Arrays.fill(expectedHash,(byte)0x35);
        check(Arrays.equals(result.savePayloadDigest(),expectedHash),"actual payload hash remains immutable");
        check(result.pixelsArgb().length==16384 && result.pixelsArgb()[0]==0xff123456 && result.pixelsArgb()[16383]==0xff123456,"actual opaque frame remains immutable");
        result.savePayloadDigest()[0]=0; result.pixelsArgb()[0]=0;
        check(result.savePayloadDigest()[0]==0x35 && result.pixelsArgb()[0]==0xff123456,"published accessors share no mutable arrays");
        check(refreshes[0]==1,"successful publication refreshes once");
        publisher.committed(snapshot(8,0)); executor.runNext();
        check(writes.get(1).mode==PetPreviewSnapshot.Mode.NORMAL && !writes.get(1).awayPaused,"Normal mapping has no away pause");
    }
    private static void productionAdapterWritesRealCache() throws Exception {
        File dir=Files.createTempDirectory("committed-preview-publisher-").toFile();
        try {
            QueueExecutor executor=new QueueExecutor();
            int[] refreshes={0};
            CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,new PetPreviewStore(dir),()->refreshes[0]++);
            publisher.committed(snapshot(7,1));
            File cache=new File(dir,"pet-preview.cache");
            check(!cache.exists(),"production adapter defers real disk publication");
            executor.runNext();
            check(cache.isFile(),"production adapter publishes cache file");
            PetPreviewSnapshot decoded=PetPreviewStore.decode(Files.readAllBytes(cache.toPath()));
            check(decoded.generation==7 && decoded.committedUtcMillis==1800000000999L && decoded.pixelsArgb()[100]==0xff123456,"real persisted cache preserves committed metadata and raw frame");
            check(refreshes[0]==1,"real store success requests refresh");
        } finally {
            for(File file:dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }
    private static void failedWriteDoesNotRefreshAndLaterRecovers() {
        QueueExecutor executor=new QueueExecutor();
        List<Long> writes=new ArrayList<>(); int[] refreshes={0};
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,s->{
            if(s.generation==7) throw new IOException("disk full");
            if(s.generation==8) throw new SecurityException("cache denied");
            writes.add(s.generation);
        },()->refreshes[0]++);
        publisher.committed(snapshot(7,0)); executor.runNext();
        publisher.committed(snapshot(8,0)); executor.runNext();
        check(writes.isEmpty() && refreshes[0]==0,"failed checked/runtime writes do not refresh or escape");
        publisher.committed(snapshot(9,0)); executor.runNext();
        check(writes.equals(Arrays.asList(9L)) && refreshes[0]==1,"later successful commit recovers after cache failures");
    }
    private static void failedRefreshDoesNotPoisonLaterCommit() {
        QueueExecutor executor=new QueueExecutor();
        List<Long> writes=new ArrayList<>(); int[] attempts={0};
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,s->writes.add(s.generation),()->{
            if(++attempts[0]==1) throw new IllegalStateException("refresh unavailable");
        });
        publisher.committed(snapshot(7,0)); executor.runNext();
        publisher.committed(snapshot(8,0)); executor.runNext();
        check(writes.equals(Arrays.asList(7L,8L)) && attempts[0]==2,"refresh failure is contained and later commit refreshes");
    }
    private static void pendingBurstUsesOneTaskAndNewestSnapshot() {
        QueueExecutor executor=new QueueExecutor();
        List<Long> writes=new ArrayList<>(); int[] refreshes={0};
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,s->writes.add(s.generation),()->refreshes[0]++);
        for(int generation=1;generation<=1000;generation++) publisher.committed(snapshot(generation,0));
        check(executor.size()==1 && executor.submissions==1,"pending burst occupies one executor task");
        executor.runNext();
        check(writes.equals(Arrays.asList(1000L)) && refreshes[0]==1,"latest pending commit wins and refreshes coalesce");
    }
    private static void slowActiveWriteKeepsOnlyLatestPendingOffOwnerThread() throws Exception {
        QueueExecutor executor=new QueueExecutor();
        List<Long> writes=new ArrayList<>(); int[] refreshes={0};
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        Thread owner=Thread.currentThread(); AtomicReference<Throwable> failure=new AtomicReference<>();
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,s->{
            check(Thread.currentThread()!=owner,"cache I/O runs off submitting owner thread");
            writes.add(s.generation);
            if(s.generation==7) { entered.countDown(); await(release); }
        },()->{ check(Thread.currentThread()!=owner,"refresh runs off owner thread");refreshes[0]++; });
        publisher.committed(snapshot(7,0));
        Thread worker=new Thread(()->{ try { executor.runNext(); } catch(Throwable t) { failure.set(t); } });
        worker.start();
        try {
            await(entered);
            for(int generation=8;generation<=1000;generation++) publisher.committed(snapshot(generation,1));
            check(executor.size()==0 && executor.submissions==1,"slow active I/O accumulates no executor queue");
        } finally { release.countDown(); join(worker); }
        if(failure.get()!=null) throw new AssertionError("worker failure",failure.get());
        check(writes.equals(Arrays.asList(7L,1000L)),"active publication finishes then only newest pending is published");
        check(refreshes[0]==1,"rapid work during active publication shares one final refresh");
    }
    private static void submissionsAtCompletionAndAfterIdleAreNotStranded() throws Exception {
        QueueExecutor executor=new QueueExecutor();
        List<Long> writes=new ArrayList<>(); int[] refreshes={0};
        AtomicReference<CommittedPreviewPublisher> publisher=new AtomicReference<>();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        publisher.set(new CommittedPreviewPublisher(executor,s->writes.add(s.generation),()->{
            if(++refreshes[0]==1) {
                Thread submitter=new Thread(()->{
                    try { publisher.get().committed(snapshot(8,1)); } catch(Throwable t) { failure.set(t); }
                });
                submitter.start(); join(submitter);
            }
        }));
        publisher.get().committed(snapshot(7,0)); executor.runNext();
        if(failure.get()!=null) throw new AssertionError("submitter failure",failure.get());
        check(writes.equals(Arrays.asList(7L,8L)) && refreshes[0]==2,"concurrent submission during final refresh drains before worker completion");
        publisher.get().committed(snapshot(9,0));
        check(executor.size()==1,"submission after idle schedules a replacement worker");
        executor.runNext();
        check(writes.equals(Arrays.asList(7L,8L,9L)) && refreshes[0]==3,"submission after completion also publishes and refreshes");
    }
    private static void invalidFrameCannotPublishAndLaterRecovers() {
        QueueExecutor executor=new QueueExecutor(); List<Long> writes=new ArrayList<>(); int[] refreshes={0};
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,s->writes.add(s.generation),()->refreshes[0]++);
        CommitSnapshot valid=snapshot(7,0);
        int[] transparent=valid.rawFrame(); transparent[500]=0x00123456;
        publisher.committed(new CommitSnapshot(valid.record,valid.payloadHash(),transparent,valid.committedUtcMs));
        executor.runNext();
        check(writes.isEmpty() && refreshes[0]==0,"existing preview constructor blocks nonopaque raw frame");
        publisher.committed(snapshot(8,0)); executor.runNext();
        check(writes.equals(Arrays.asList(8L)) && refreshes[0]==1,"invalid derived preview does not poison future commits");
    }
    private static void rejectedExecutorDoesNotEscapeAndLaterRecovers() {
        QueueExecutor executor=new QueueExecutor(); executor.reject=true;
        List<Long> writes=new ArrayList<>();
        CommittedPreviewPublisher publisher=new CommittedPreviewPublisher(executor,s->writes.add(s.generation),()->{});
        publisher.committed(snapshot(7,0));
        executor.reject=false; publisher.committed(snapshot(8,0));
        check(executor.size()==1,"later submission can restart after executor rejection");
        executor.runNext();
        check(writes.equals(Arrays.asList(8L)),"rejected task cannot poison subsequent publication");
    }
    private interface Test { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        String[] names={"exact metadata/immutable input","production adapter","write failure recovery","refresh failure recovery",
                "bounded pending burst","bounded active/off-owner I/O","completion handoff","opaque frame validation","executor rejection recovery"};
        Test[] tests={CommittedPreviewPublisherTest::exactMetadataAndImmutableInput,CommittedPreviewPublisherTest::productionAdapterWritesRealCache,
                CommittedPreviewPublisherTest::failedWriteDoesNotRefreshAndLaterRecovers,CommittedPreviewPublisherTest::failedRefreshDoesNotPoisonLaterCommit,
                CommittedPreviewPublisherTest::pendingBurstUsesOneTaskAndNewestSnapshot,CommittedPreviewPublisherTest::slowActiveWriteKeepsOnlyLatestPendingOffOwnerThread,
                CommittedPreviewPublisherTest::submissionsAtCompletionAndAfterIdleAreNotStranded,CommittedPreviewPublisherTest::invalidFrameCannotPublishAndLaterRecovers,
                CommittedPreviewPublisherTest::rejectedExecutorDoesNotEscapeAndLaterRecovers};
        int failures=0;
        for(int i=0;i<tests.length;i++) {
            try { tests[i].run(); System.out.println("PASS "+names[i]); }
            catch(Throwable failure) { failures++; System.out.println("FAIL "+names[i]+": "+failure); }
        }
        if(failures>0) throw new AssertionError(failures+" publisher tests failed");
        System.out.println("CommittedPreviewPublisherTest: "+tests.length+" tests, "+checks+" checks passed");
    }
}
