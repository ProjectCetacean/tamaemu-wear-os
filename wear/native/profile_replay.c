/* Root-only aggregate profiler. Never linked into the production APK.
 * Build alongside an instrumented backend.c object with:
 * -Dcpu_step=profile_cpu_step -Dcpu_skip_idle=profile_cpu_skip_idle
 * -Dperiph_tick_at=profile_periph_tick_at
 * Link the usual standalone core sources. To measure the reviewed baseline,
 * build its unchanged backend.c with the same three symbol substitutions.
 * Explicit input files are cloned into a newly created output directory.
 * No discovery, guest addresses, raw data, hashes, or frames are printed. */
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include <errno.h>
#include <inttypes.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

static uint64_t cpu,active,halted,idle_calls,idle_steps,ticks,tick_cycles;
static uint64_t reason[8];
static void classify(const Emu *e) {
    unsigned why=7;
    if(!e->halted) why=0;
    else if(e->pending_irq || e->wake_req) why=1;
    else if(!e->halt_hard) why=2;
    else if(e->flash_busy || e->flash_state || e->flash_autoselect) why=3;
    else if(e->cmu.mclk_hz<1048576) why=4;
    else {
        const uint8_t *io=e->ioram;
        for(unsigned i=0;i<6;i++) if(io[0x786+i*8]&1) {why=5;break;}
        if(why==7 && (e->ir_tx_ready_at || e->link || e->dev.ir_gpio || e->dev.nfc_pn512 ||
            io[e->dev.ir_base+5-e->dev.io_base])) why=6;
    }
    reason[why]++;
}
void profile_cpu_step(Emu *e) {cpu++;if(e->halted) halted++;else active++;classify(e);cpu_step(e);}
unsigned profile_cpu_skip_idle(Emu *e,unsigned n) {idle_calls++;classify(e);unsigned r=cpu_skip_idle(e,n);idle_steps+=r;return r;}
void profile_periph_tick_at(Emu *e,uint32_t n,double hz) {ticks++;tick_cycles+=n;periph_tick_at(e,n,hz);}
static double now(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
static int clone(const char *src,const char *dst) {
    FILE *in=fopen(src,"rb"),*out=NULL;int ok=0;
    if(!in) return 0;
    out=fopen(dst,"wbx");if(!out) {fclose(in);return 0;}
    unsigned char buf[16384];size_t n;
    while((n=fread(buf,1,sizeof buf,in))) if(fwrite(buf,1,n,out)!=n) goto done;
    ok=!ferror(in);
done:
    if(fclose(out)) ok=0;if(fclose(in)) ok=0;return ok;
}
int main(int argc,char **argv) {
    /* Core diagnostics can contain guest locations. Suppress both streams
     * before opening/executing any input; report aggregates through a separate
     * stream to the original stdout only after the core work has returned. */
    int report_fd=dup(STDOUT_FILENO);FILE *report=report_fd<0?NULL:fdopen(report_fd,"w");
    if(!report || !freopen("/dev/null","w",stdout) || !freopen("/dev/null","w",stderr)) return 2;
    if(argc!=5) {fprintf(report,"Usage: profile_replay explicit-rom explicit-save new-output-dir seconds\n");return 2;}
    char *end=NULL;errno=0;unsigned long seconds=strtoul(argv[4],&end,10);
    if(errno || !*argv[4] || *end || seconds<1 || seconds>259200) {fprintf(report,"Invalid interval\n");return 2;}
    if(mkdir(argv[3],0700)) {fprintf(report,"Output directory must be new\n");return 2;}
    size_t n=strlen(argv[3])+32;char *rom=malloc(n),*save=malloc(n);
    if(!rom || !save) {fprintf(report,"Allocation failed\n");return 2;}
    snprintf(rom,n,"%s/input.rom",argv[3]);snprintf(save,n,"%s/input.state",argv[3]);
    if(!clone(argv[1],rom) || !clone(argv[2],save)) {fprintf(report,"Explicit input clone failed\n");return 2;}
    char error[160];WearBackend *b=wear_open(rom,save,error);
    if(!b) {fprintf(report,"Cloned input open failed\n");return 1;}
    const Emu *e=wear_state(b);double guest=e->emu_secs,start=now();uint64_t cycles=e->cycles;
    uint64_t initial_residue=e->cycles-e->last_tick;
    double initial_hz=e->cmu.mclk_hz;int initial_halt=e->halted;unsigned completed=0,clock_changes=0;double hz=initial_hz;
    for(unsigned i=0;i<seconds*10;i++) {
        if(now()-start>=55 || !wear_advance_headless(b,100,error)) break;
        completed++;if(hz!=e->cmu.mclk_hz) {hz=e->cmu.mclk_hz;clock_changes++;}
    }
    double wall=now()-start,delta=e->emu_secs-guest;uint64_t elapsed=e->cycles-cycles;
    /* After each successful request last_tick==cycles. On early failure only
     * a sub-tick residue can remain; never infer a fractional batched tick. */
    uint64_t accounted=elapsed+initial_residue-(e->cycles-e->last_tick);
    uint64_t batched=accounted>=tick_cycles?(accounted-tick_cycles)/256:0;
    fprintf(report,"{\"complete\":%s,\"requested_seconds\":%lu,\"completed_ms\":%u,\"guest_delta_seconds\":%.9f,\"wall_seconds\":%.9f,\"initial_mclk_hz\":%.3f,\"initial_halted\":%d,\"request_boundary_clock_changes\":%u,\"cpu_steps\":%"PRIu64",\"active_cpu_steps\":%"PRIu64",\"halted_cpu_steps\":%"PRIu64",\"idle_calls\":%"PRIu64",\"ordinary_idle_steps\":%"PRIu64",\"literal_ticks\":%"PRIu64",\"batched_ticks\":%"PRIu64",\"ordinary_call_reasons\":[",
        completed==seconds*10?"true":"false",seconds,completed*100,delta,wall,initial_hz,initial_halt,clock_changes,cpu,active,halted,idle_calls,idle_steps,ticks,batched);
    for(unsigned i=0;i<8;i++) fprintf(report,"%s%"PRIu64,i?",":"",reason[i]);
    fprintf(report,"],\"reason_order\":[\"active\",\"irq_or_wake\",\"soft_halt\",\"flash\",\"low_clock\",\"timer\",\"external_io\",\"boundary_or_other_guard\"],\"equivalence_checked\":false}\n");
    wear_close(b);free(rom);free(save);fclose(report);return completed==seconds*10?0:1;
}
