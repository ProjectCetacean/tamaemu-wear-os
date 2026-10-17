#define _DARWIN_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/wait.h>
#include <unistd.h>

/* Host-only feasibility probe. Paths and firmware/save content never go to
 * stdout/stderr. The caller supplies a disposable copy under ignored build/. */
static double seconds(clockid_t clock) {
    struct timespec t;if(clock_gettime(clock,&t)) return -1;
    return (double)t.tv_sec+(double)t.tv_nsec/1e9;
}
static void digest_hex(const uint8_t digest[32],char out[65]) {
    for(int i=0;i<32;i++) snprintf(out+2*i,3,"%02x",digest[i]);
}
static int positive_integer(const char *s,int64_t *value) {
    if(!s || !*s || *s<'0' || *s>'9') return 0;
    char *end;errno=0;long long n=strtoll(s,&end,10);
    if(errno || *end || n<1) return 0;*value=(int64_t)n;return 1;
}
static int copy_fixture(const char *input,const char *copy) {
    FILE *in=fopen(input,"rb");if(!in) return 0;
    int fd=open(copy,O_CREAT|O_EXCL|O_WRONLY,0600);
    FILE *out=fd<0?NULL:fdopen(fd,"wb");
    if(!out) {if(fd>=0) close(fd);fclose(in);return 0;}
    char bytes[8192];size_t n;int ok=1;
    while((n=fread(bytes,1,sizeof bytes,in))) if(fwrite(bytes,1,n,out)!=n) {ok=0;break;}
    if(ferror(in)) ok=0;
    if(fclose(out)) ok=0;fclose(in);return ok;
}
static int run_replay(int argc,char **argv,const char *owned_copy) {
    /* Upstream emits flash writes even without log flags. Isolate those
     * diagnostics so this measurement tool can never disclose guest bytes. */
    FILE *diagnostics=fdopen(dup(STDERR_FILENO),"w");int sink=open("/dev/null",O_WRONLY);
    if(!diagnostics || sink<0 || dup2(sink,STDERR_FILENO)<0) return 1;
    close(sink);
    int64_t requested,budget=60;
    if(argc<4 || argc>5 || !positive_integer(argv[3],&requested) ||
       (argc==5 && !positive_integer(argv[4],&budget)) || budget>60) {
        fprintf(diagnostics,"Usage: bench_replay ROM_PATH SAVE_COPY_PATH EMULATED_MILLISECONDS [WALL_BUDGET_SECONDS:1..60]\n");return 1;
    }
    const char *copy=owned_copy;if(!copy_fixture(argv[2],copy)) {fprintf(diagnostics,"Cannot copy replay fixture\n");return 1;}
    char error[160];WearBackend *plain=wear_open(argv[1],copy,error),*rendered=NULL;
    uint32_t *pixels=NULL;int result=1;
    if(!plain) {fprintf(diagnostics,"Cannot open replay fixture: %s\n",error);goto done;}
    rendered=wear_open(argv[1],argv[2],error);pixels=malloc(PANEL_W*PANEL_H*sizeof *pixels);
    if(!rendered || !pixels) {fprintf(diagnostics,"Cannot allocate replay reference\n");goto done;}
    uint8_t initial[32],final[32],reference[32];char initial_hex[65],final_hex[65];
    wear_state_digest(plain,initial);digest_hex(initial,initial_hex);
    const Emu *e=wear_state(plain);double initial_guest=e->emu_secs;
    printf("profile=idl initial_digest=%s initial_guest_seconds=%.9f mclk_hz=%.0f halted=%d lcd_sleep_out=%d lcd_display_on=%d\n",
           initial_hex,initial_guest,e->cmu.mclk_hz,e->halted,e->lcd.sleep_out,e->lcd.disp_on);
    double start=seconds(CLOCK_MONOTONIC),headless_wall=0,rendered_wall=0,headless_cpu=0,rendered_cpu=0;
    int64_t completed=0;
    /* One matched pair is the cancellation boundary; each API call retains
     * the existing 100ms and 10-million-instruction bounds. */
    while(completed<requested && seconds(CLOCK_MONOTONIC)-start<(double)budget) {
        int ms=requested-completed>100?100:(int)(requested-completed);
        double wall=seconds(CLOCK_MONOTONIC),cpu=seconds(CLOCK_PROCESS_CPUTIME_ID);
        int ok=wear_advance_headless(plain,ms,error);
        headless_cpu+=seconds(CLOCK_PROCESS_CPUTIME_ID)-cpu;headless_wall+=seconds(CLOCK_MONOTONIC)-wall;
        if(!ok) {fprintf(diagnostics,"Headless replay failed: %s\n",error);goto done;}
        wall=seconds(CLOCK_MONOTONIC);cpu=seconds(CLOCK_PROCESS_CPUTIME_ID);
        ok=wear_advance(rendered,ms,pixels,PANEL_W*PANEL_H,error);
        rendered_cpu+=seconds(CLOCK_PROCESS_CPUTIME_ID)-cpu;rendered_wall+=seconds(CLOCK_MONOTONIC)-wall;
        if(!ok) {fprintf(diagnostics,"Rendered reference failed: %s\n",error);goto done;}
        completed+=ms;
    }
    double replay_wall=seconds(CLOCK_MONOTONIC)-start;
    wear_state_digest(plain,final);wear_state_digest(rendered,reference);digest_hex(final,final_hex);
    int equivalent=!memcmp(final,reference,32);
    double save_start=seconds(CLOCK_MONOTONIC);int saved=wear_save(plain,error);
    double save_wall=seconds(CLOCK_MONOTONIC)-save_start;
    WearBackend *reopened=saved?wear_open(argv[1],copy,error):NULL;
    int reopen_equal=0;if(reopened) {wear_state_digest(reopened,reference);reopen_equal=!memcmp(final,reference,32);wear_close(reopened);}
    printf("status=%s requested_ms=%" PRId64 " completed_ms=%" PRId64 " guest_delta_seconds=%.9f replay_wall_seconds=%.6f budget_seconds=%" PRId64 "\n",
           completed==requested?"complete":"incomplete",requested,completed,e->emu_secs-initial_guest,replay_wall,budget);
    printf("headless_wall_seconds=%.6f headless_cpu_seconds=%.6f headless_realtime_ratio=%.6f rendered_wall_seconds=%.6f rendered_cpu_seconds=%.6f rendered_realtime_ratio=%.6f save_wall_seconds=%.6f\n",
           headless_wall,headless_cpu,headless_wall>0?(double)completed/1000/headless_wall:0,
           rendered_wall,rendered_cpu,rendered_wall>0?(double)completed/1000/rendered_wall:0,save_wall);
    printf("canonical_guest_nor_and_pacing_equal=%d save_reopen_equal=%d final_digest=%s\n",equivalent,reopen_equal,final_hex);
    if(!equivalent || !reopen_equal) {fprintf(diagnostics,"Replay equivalence or checkpoint failed\n");result=1;}
    else result=completed==requested?0:2;
done:
    free(pixels);wear_close(rendered);wear_close(plain);return result;
}

/* Only this newly created private directory is eligible for cleanup. Atomic
 * save temporaries stay inside it, including when the child is killed mid-save. */
static int clean_owned_directory(const char *directory) {
    DIR *dir=opendir(directory);int ok=dir!=NULL;
    if(dir) {
        struct dirent *entry;
        while((entry=readdir(dir))) {
            if(!strcmp(entry->d_name,".") || !strcmp(entry->d_name,"..")) continue;
            if(unlinkat(dirfd(dir),entry->d_name,0)) ok=0;
        }
        if(closedir(dir)) ok=0;
    }
    if(rmdir(directory)) ok=0;return ok;
}
int main(int argc,char **argv) {
    double started=seconds(CLOCK_MONOTONIC);int64_t requested,budget=60;
    if(argc<4 || argc>5 || !positive_integer(argv[3],&requested) ||
       (argc==5 && !positive_integer(argv[4],&budget)) || budget>60) {
        fprintf(stderr,"Usage: bench_replay ROM_PATH SAVE_COPY_PATH EMULATED_MILLISECONDS [WALL_BUDGET_SECONDS:1..60]\n");return 1;
    }
    size_t size=strlen(argv[2])+40;char *directory=malloc(size),*copy=malloc(size+32);
    if(!directory || !copy) {free(directory);free(copy);return 1;}
    snprintf(directory,size,"%s.probe-XXXXXX",argv[2]);
    if(!mkdtemp(directory)) {fprintf(stderr,"Cannot create disposable replay directory\n");free(directory);free(copy);return 1;}
    snprintf(copy,size+32,"%s/checkpoint.state",directory);
    int output[2];pid_t child=-1;
    if(pipe(output)) goto setup_failed;
    child=fork();
    if(child<0) {close(output[0]);close(output[1]);goto setup_failed;}
    if(!child) {
        close(output[0]);
        if(dup2(output[1],STDOUT_FILENO)<0) _exit(1);
        close(output[1]);exit(run_replay(argc,argv,copy));
    }
    close(output[1]);
    int flags=fcntl(output[0],F_GETFL),failed=0,timed_out=0,reaped=0,status=0;
    if(flags<0 || fcntl(output[0],F_SETFL,flags|O_NONBLOCK)<0) failed=1;
    char captured[8192];size_t used=0;
    while(!failed) {
        /* A delayed supervisor must classify expiry before accepting any
         * buffered child result, even when the child finished while stopped. */
        if(seconds(CLOCK_MONOTONIC)-started>=(double)budget) {timed_out=1;break;}
        ssize_t n;
        while(used<sizeof captured && (n=read(output[0],captured+used,sizeof captured-used))>0) used+=(size_t)n;
        if(used==sizeof captured) {failed=1;break;}
        pid_t observed=waitpid(child,&status,WNOHANG);
        if(observed==child) {
            reaped=1;
            if(seconds(CLOCK_MONOTONIC)-started>=(double)budget) timed_out=1;
            break;
        }
        if(observed<0 && errno!=EINTR) {failed=1;break;}
        double remaining=(double)budget-(seconds(CLOCK_MONOTONIC)-started);
        if(remaining<=0) {timed_out=1;break;}
        struct pollfd fd={output[0],POLLIN,0};
        int milliseconds=remaining>0.05?50:(int)ceil(remaining*1000);
        if(poll(&fd,1,milliseconds)<0 && errno!=EINTR) {failed=1;break;}
    }
    if(!reaped) {
        /* An unreaped child still owns its PID. Never signal after reap. */
        kill(child,SIGKILL);
        pid_t observed;
        do {observed=waitpid(child,&status,0);} while(observed<0 && errno==EINTR);
        if(observed==child) reaped=1;else failed=1;
    } else {
        /* The child has exited, so this final nonblocking drain cannot wait
         * on stdout or on a descendant retaining an inherited descriptor. */
        ssize_t n;
        while(used<sizeof captured && (n=read(output[0],captured+used,sizeof captured-used))>0) used+=(size_t)n;
        if(used==sizeof captured) failed=1;
    }
    close(output[0]);
    if(!clean_owned_directory(directory)) failed=1;
    free(directory);free(copy);
    double total=seconds(CLOCK_MONOTONIC)-started;
    /* Reap, final drain and cleanup also consume the global budget. Discard
     * comparison claims if any of them finished after the deadline. */
    if(total>=(double)budget) timed_out=1;
    if(timed_out && !failed) {
        printf("status=incomplete reason=wall_budget requested_ms=%" PRId64 " completed_ms=unknown equivalence=unverified checkpoint=unverified total_wall_seconds=%.6f budget_seconds=%" PRId64 "\n",requested,total,budget);
        return 2;
    }
    if(failed || !WIFEXITED(status)) {fprintf(stderr,"Replay supervisor or cleanup failed\n");return 1;}
    fwrite(captured,1,used,stdout);printf("total_wall_seconds=%.6f budget_seconds=%" PRId64 "\n",total,budget);
    return WEXITSTATUS(status);
setup_failed:
    clean_owned_directory(directory);free(directory);free(copy);
    fprintf(stderr,"Cannot start replay supervisor\n");return 1;
}
