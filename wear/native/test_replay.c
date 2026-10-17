#define _DARWIN_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Independent original backend/peripheral loops live only in the test target. */
WearBackend *replay_reference_open(const char *,const char *,char [160]);
void replay_reference_close(WearBackend *);
const Emu *replay_reference_state(const WearBackend *);
void replay_reference_state_digest(const WearBackend *,uint8_t [32]);
int replay_reference_advance_headless(WearBackend *,int,char [160]);
int replay_reference_save(WearBackend *,char [160]);
int replay_reference_button(WearBackend *,int,int);
int replay_reference_write_life(WearBackend *,const uint64_t [8],char [160]);
void replay_reference_periph_tick(Emu *,uint32_t);

#define CHECK(c,label) do {if(!(c)) {fprintf(stderr,"FAIL: %s (line %d)\n",label,__LINE__);exit(1);} checks++;} while(0)
static uint64_t checks,cpu_dispatches,idle_batches;
static char error[160];
void replay_counted_cpu_step(Emu *e) {cpu_dispatches++;cpu_step(e);}
unsigned replay_counted_cpu_skip_idle(Emu *e,unsigned n) {idle_batches++;return cpu_skip_idle(e,n);}

static void equal(WearBackend *a,WearBackend *b) {
    uint8_t x[32],y[32];wear_state_digest(a,x);replay_reference_state_digest(b,y);
    CHECK(!memcmp(x,y,sizeof x),"original slow backend matches every canonical guest/NOR/pacing/life byte");
}
static void pair_advance(WearBackend *a,WearBackend *b,int ms) {
    int x=wear_advance_headless(a,ms,error),y=replay_reference_advance_headless(b,ms,error);
    CHECK(x==y,"original and candidate advance/failure statuses match");equal(a,b);
}
static void fixture(const char *rom) {
    uint8_t *image=calloc(1,PS_ROM_SIZE);CHECK(image!=NULL,"synthetic allocation");
    /* All vectors enter NOP, HALT. No copyrighted guest program is needed. */
    for(int vector=0;vector<96;vector++) {
        uint32_t pc=0x02401000;for(int byte=0;byte<4;byte++) image[0x400000+vector*4+byte]=(uint8_t)(pc>>(byte*8));
    }
    image[0x401002]=0x80;
    FILE *f=fopen(rom,"wb");CHECK(f && fwrite(image,1,PS_ROM_SIZE,f)==PS_ROM_SIZE,"synthetic fixture write");
    CHECK(fclose(f)==0,"synthetic close");free(image);
}
static void configure(Emu *e,unsigned residue,int mode) {
    e->halted=true;e->halt_hard=mode!=1;e->cmu.mclk_hz=16384;e->cycles=residue;
    e->wake_at=e->cycles+31;e->sp=0x7000;
    if(mode==2) {e->pending_irq=65;e->pending_level=4;e->f_ie=1;}
    if(mode==3) {e->pending_irq=65;e->pending_level=4;e->f_ie=0;}
    if(mode==4) e->wake_req=true;
    e->lcd.gram[4][3]=0x1234;e->tone_ev_w=1;e->tone_ev[0].cyc=residue;
}
static void equal_emu(Emu *x,Emu *y) {
    uint8_t *rom=y->rom;y->rom=x->rom;
    CHECK(!memcmp(x,y,sizeof *x),"all CPU/peripheral/trace bytes equal original single steps");y->rom=rom;
}
static void idle_boundaries(const char *rom,const char *save,const char *reference) {
    WearBackend *a=wear_open(rom,save,error),*b=replay_reference_open(rom,reference,error);
    CHECK(a&&b,"idle boundary branches open");
    Emu *x=(Emu *)wear_state(a),*y=(Emu *)replay_reference_state(b);
    Emu *initial=malloc(sizeof *initial);CHECK(initial!=NULL,"idle baseline allocation");memcpy(initial,x,sizeof *x);
    const unsigned limits[]={0,1,2,15,16,17,10000000};
    const uint64_t starts[]={0,UINT64_MAX-300,UINT64_MAX-32,UINT64_MAX-15,UINT64_MAX};
    for(size_t start=0;start<sizeof starts/sizeof *starts;start++) for(unsigned r=0;r<256;r++)
    for(int mode=0;mode<7;mode++) for(int wake=0;wake<34;wake++) {
        uint8_t *yr=y->rom;memcpy(x,initial,sizeof *x);memcpy(y,initial,sizeof *y);y->rom=yr;
        configure(x,r,mode);configure(y,r,mode);
        x->cycles=y->cycles=starts[start];x->last_tick=y->last_tick=x->cycles-r;
        x->wake_at=y->wake_at=x->cycles+(unsigned)wake;
        x->stopped=y->stopped=mode==5;x->halted=y->halted=mode!=6;
        unsigned limit=limits[(r+mode+wake)%7],n=cpu_skip_idle(x,limit);
        CHECK(n<=limit,"idle skip honors remaining work allowance");
        if(n) for(unsigned i=0;i<n;i++) cpu_step(y);
        else if(limit) {cpu_step(x);cpu_step(y);}
        equal_emu(x,y);
        /* A skip must reach no later than the original next tick boundary. */
        if(n) CHECK(n<=16,"idle batch stays inside one peripheral interval");
    }
    free(initial);wear_close(a);replay_reference_close(b);
}
static void backend_equivalence(const char *rom,const char *save,const char *reference) {
    WearBackend *a=wear_open(rom,save,error),*b=replay_reference_open(rom,reference,error);
    CHECK(a&&b,"backend branches open");
    configure((Emu *)wear_state(a),255,1);configure((Emu *)replay_reference_state(b),255,1);
    const uint64_t life[]={1,1000,2000,3,4000,0,1,0};
    CHECK(wear_write_life(a,life,error)&&replay_reference_write_life(b,life,error),"life metadata fixture");
    const int chunks[]={0,-1,1,17,33,100,1000,7};
    for(int round=0;round<3;round++) for(size_t n=0;n<sizeof chunks/sizeof *chunks;n++) pair_advance(a,b,chunks[n]);
    const double hz[]={18432000,32768,16384,200000000};
    for(int round=0;round<8;round++) {
        ((Emu *)wear_state(a))->cmu.mclk_hz=hz[round%4];
        ((Emu *)replay_reference_state(b))->cmu.mclk_hz=hz[round%4];
        CHECK(wear_button(a,round%3,round%2)==replay_reference_button(b,round%3,round%2),"same button edge");
        pair_advance(a,b,100);
    }
    CHECK(wear_save(a,error)&&replay_reference_save(b,error),"both independent writers checkpoint");
    wear_close(a);replay_reference_close(b);
    a=wear_open(rom,save,error);b=replay_reference_open(rom,reference,error);CHECK(a&&b,"both checkpoints reopen");equal(a,b);
    for(int i=0;i<4;i++) pair_advance(a,b,100);
    wear_close(a);replay_reference_close(b);
    CHECK(unlink(save)==0 && unlink(reference)==0,"owned checkpoints removed");

    idle_boundaries(rom,save,reference);
}
static void dispatch_bound(const char *rom,const char *save,const char *reference) {
    WearBackend *a=wear_open(rom,save,error),*b=replay_reference_open(rom,reference,error);
    CHECK(a&&b,"dispatch branches open");configure((Emu *)wear_state(a),0,0);configure((Emu *)replay_reference_state(b),0,0);
    ((Emu *)wear_state(a))->cmu.mclk_hz=18432000;
    ((Emu *)replay_reference_state(b))->cmu.mclk_hz=18432000;
    cpu_dispatches=idle_batches=0;pair_advance(a,b,100);
    printf("idle_guest_ms=100 cpu_dispatches=%" PRIu64 " idle_batches=%" PRIu64 "\n",cpu_dispatches,idle_batches);
    /* Catches accidentally restoring per-16-cycle interpreter dispatch. */
    CHECK(cpu_dispatches+idle_batches<8000,"100ms idle replay avoids per-16-cycle CPU dispatch");
    wear_close(a);replay_reference_close(b);
}
static void work_limit(const char *rom,const char *save,const char *reference) {
    WearBackend *a=wear_open(rom,save,error),*b=replay_reference_open(rom,reference,error);
    CHECK(a&&b,"work-limit branches open");configure((Emu *)wear_state(a),0,0);configure((Emu *)replay_reference_state(b),0,0);
    /* Deliberately beyond stored-state clock bounds: hit the runtime guard. */
    ((Emu *)wear_state(a))->cmu.mclk_hz=2000000000;
    ((Emu *)replay_reference_state(b))->cmu.mclk_hz=2000000000;
    CHECK(!wear_advance_headless(a,100,error)&&!replay_reference_advance_headless(b,100,error),"both paths retain ten-million-step failure");
    equal(a,b);
    CHECK(wear_state(a)->cycles==160000000,"batched idle charges all ten million original steps");
    wear_close(a);replay_reference_close(b);
}
static void peripheral_continuation(const char *rom,const char *save,const char *reference) {
    static const uint8_t dates[][6]={{0x28,2,0x24,0x29,2,0x24},{0x28,2,0x25,1,3,0x25},{0x31,0x12,0x99,1,1,0}};
    for(size_t n=0;n<sizeof dates/sizeof *dates;n++) {
        WearBackend *a=wear_open(rom,save,error),*b=replay_reference_open(rom,reference,error);CHECK(a&&b,"calendar/IRQ branches open");
        Emu *x=(Emu *)wear_state(a),*y=(Emu *)replay_reference_state(b);configure(x,0,0);configure(y,0,0);
        Emu *branches[]={x,y};
        for(int side=0;side<2;side++) {
            Emu *e=branches[side];e->rtc_sec_frac=0.9;e->rtc_irq_frac=0.015;e->f_ie=1;
            mem_write8(e,0x301910,0x59);mem_write8(e,0x301914,0x59);mem_write8(e,0x301918,0x23);
            mem_write8(e,0x30191c,dates[n][0]);mem_write8(e,0x301920,dates[n][1]);mem_write8(e,0x301924,dates[n][2]);
            mem_write8(e,0x301928,6);mem_write8(e,0x301904,1);mem_write8(e,0x300277,4);mem_write8(e,0x30026a,0x40);
            mem_write8(e,0x300780,1);mem_write8(e,0x300782,3);mem_write8(e,0x3007e0,8);mem_write8(e,0x300786,5);
            mem_write8(e,0x300272,0x0c);mem_write8(e,0x300266,3);
        }
        pair_advance(a,b,100);pair_advance(a,b,100);
        CHECK(x->rtc_seconds_counted==1,"midnight advances exactly one RTC second");
        CHECK(mem_read8(x,0x30191c)==dates[n][3] && mem_read8(x,0x301920)==dates[n][4] && mem_read8(x,0x301924)==dates[n][5],"literal leap/non-leap/year calendar carry");
        CHECK(x->t16_fires[0]>0 && x->irq_taken[65]>0 && x->tone_ev_w>1,"timer, prioritized RTC wake and tone events continue");
        wear_close(a);replay_reference_close(b);
    }
}
int main(int argc,char **argv) {
    char directory[]="/tmp/tamaemu-replay-test-XXXXXX";CHECK(mkdtemp(directory)!=NULL,"owned test directory");
    char rom[256],save[256],reference[256];
    snprintf(rom,sizeof rom,"%s/synthetic.rom",directory);snprintf(save,sizeof save,"%s/new.state",directory);
    snprintf(reference,sizeof reference,"%s/original.state",directory);fixture(rom);
    if(argc==2 && !strcmp(argv[1],"--dispatch")) dispatch_bound(rom,save,reference);
    else {backend_equivalence(rom,save,reference);work_limit(rom,save,reference);peripheral_continuation(rom,save,reference);}
    CHECK(unlink(rom)==0 && rmdir(directory)==0,"owned fixtures removed");
    printf("Replay checks passed: %" PRIu64 "\n",checks);return 0;
}
