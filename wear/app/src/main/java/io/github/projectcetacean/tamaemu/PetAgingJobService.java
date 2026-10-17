package io.github.projectcetacean.tamaemu;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Main callbacks only enqueue work. Native calls and durability stay on the app's one worker. */
public final class PetAgingJobService extends JobService {
    static final int JOB_ID=0x54414d41;
    static final long WALL_BUDGET_MS=60_000;
    private LifeJobs<JobParameters> jobs;

    @Override public void onCreate() {
        super.onCreate();
        EmulatorSession session=((TamaemuApplication)getApplication()).session();
        jobs=new LifeJobs<>(new LifeJobs.Runner() {
            public void run(JobLease lease,JobCompletion completion) {
                session.runBackground(lease,WALL_BUDGET_MS,completion);
            }
            public void cancel(JobLease lease) { session.cancelBackground(lease); }
        },parameters->jobFinished(parameters,false));
    }
    @Override public boolean onStartJob(JobParameters parameters) {
        try { jobs.start(parameters);return true; }
        catch(RuntimeException unavailable) {
            ((TamaemuApplication)getApplication()).session().schedulerStatus("Background aging unavailable",true);
            return false;
        }
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        jobs.stop(parameters);
        // Periodic scheduling supplies the next opportunity without quota-bypassing re-enqueues.
        return false;
    }
    @Override public void onDestroy() {
        if(jobs!=null)jobs.close();
        super.onDestroy();
    }
}
