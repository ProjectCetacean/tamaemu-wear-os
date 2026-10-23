#define _DARWIN_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include <fenv.h>
#include <inttypes.h>
#include <limits.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

WearBackend *replay_reference_open(const char *,const char *,char [160]);
void replay_reference_close(WearBackend *);
const Emu *replay_reference_state(const WearBackend *);
void replay_reference_state_digest(const WearBackend *,uint8_t [32]);
int replay_reference_advance_headless(WearBackend *,int,char [160]);
int replay_reference_save(WearBackend *,char [160]);
int replay_reference_write_life(WearBackend *,const uint64_t [8],char [160]);
int replay_reference_button(WearBackend *,int,int);

static uint64_t checks,ticks,dispatches,idle_calls;
static char error[160];
#define CHECK(c,s) do {if(!(c)) {fprintf(stderr,"FAIL %s line %d\n",s,__LINE__);exit(1);} checks++;} while(0)
#define IO(e,a) ((e)->ioram[(a)-(e)->dev.io_base])
void fast_counted_cpu_step(Emu *e) {dispatches++;cpu_step(e);}
unsigned fast_counted_cpu_skip_idle(Emu *e,unsigned n) {idle_calls++;return cpu_skip_idle(e,n);}
void fast_counted_periph_tick_at(Emu *e,uint32_t n,double hz) {ticks++;periph_tick_at(e,n,hz);}
static double now(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec*1e-9;}
static void fixture(const char *path) {
    uint8_t *p=calloc(1,PS_ROM_SIZE);CHECK(p,"fixture allocation");
    for(int i=0;i<96;i++) {uint32_t pc=0x02401000;for(int j=0;j<4;j++) p[0x400000+i*4+j]=(uint8_t)(pc>>(8*j));}
    /* NOP, hard HALT. Subsequent wake returns to zero-filled NOP memory. */
    p[0x401002]=0x80;
    FILE *f=fopen(path,"wb");CHECK(f && fwrite(p,1,PS_ROM_SIZE,f)==PS_ROM_SIZE,"fixture write");
    CHECK(!fclose(f),"fixture close");free(p);
}
static void setup(Emu *e) {
    e->halted=true;e->halt_hard=true;e->cmu.mclk_hz=18432000;e->sp=0x7000;e->pc=0x02401000;
    e->lcd.gram[2][7]=0x1234;e->tone_ev_w=1;e->tone_ev[0].cyc=e->cycles;
}
static void equal(WearBackend *a,WearBackend *b) {
    const Emu *x=wear_state(a),*y=replay_reference_state(b);
    Emu *copy=malloc(sizeof *copy);CHECK(copy,"raw copy");memcpy(copy,y,sizeof *copy);copy->rom=x->rom;
    CHECK(!memcmp(x,copy,sizeof *copy),"raw CPU/peripheral/RAM/RTC/trace/LCD state");free(copy);
    CHECK(!memcmp(x->rom,y->rom,x->dev.rom_size),"all NOR bytes");
    uint8_t dx[32],dy[32];wear_state_digest(a,dx);replay_reference_state_digest(b,dy);
    CHECK(!memcmp(dx,dy,32),"canonical guest/deadline/life digest");
}
static void advance(WearBackend *a,WearBackend *b,int ms) {
    int x=wear_advance_headless(a,ms,error),y=replay_reference_advance_headless(b,ms,error);
    CHECK(x==y,"advance result");equal(a,b);
}
static void performance(const char *rom,const char *save) {
    WearBackend *a=wear_open(rom,save,error);CHECK(a,"performance open");setup((Emu *)wear_state(a));
    ticks=dispatches=idle_calls=0;double start=now();CHECK(wear_advance_headless(a,100,error),"performance advance");
    printf("high_clock_100ms wall=%.9f ticks=%"PRIu64" cpu=%"PRIu64" idle_calls=%"PRIu64"\n",now()-start,ticks,dispatches,idle_calls);
    CHECK(ticks<128,"unchanged high-clock halted intervals avoid per-tick traversal");wear_close(a);
}
static void matrix(const char *rom,const char *save,const char *ref) {
    WearBackend *a=wear_open(rom,save,error),*b=replay_reference_open(rom,ref,error);CHECK(a&&b,"pair open");
    Emu *x=(Emu *)wear_state(a),*y=(Emu *)replay_reference_state(b);setup(x);setup(y);
    const uint64_t life[]={1,1000,2000,3,4000,0,1,0};
    CHECK(wear_write_life(a,life,error)&&replay_reference_write_life(b,life,error),"life fixture");
    advance(a,b,0);advance(a,b,-1);
    for(unsigned residue=0;residue<256;residue++) {
        x->last_tick=y->last_tick=x->cycles-residue;
        x->trace_ring_pos=y->trace_ring_pos=TRACE_RING_N-8;
        advance(a,b,1);
    }
    /* Rounding binades, exact half-ULP ties, RTC event edges, held/stopped RTC,
     * and period selection. The frozen reference performs every addition. */
    const double hz[]={18432000,200000000,1048576,16384,32768,12345678};
    const double frac[]={0,0x1p-100,0x1p-20,nextafter(0.015625,0),0.015625,
        nextafter(0.5,0),0.5,nextafter(1,0),0.99999};
    for(unsigned i=0;i<90;i++) {
        x->cmu.mclk_hz=y->cmu.mclk_hz=hz[i%6];
        x->rtc_sec_frac=y->rtc_sec_frac=frac[i%9];
        x->rtc_irq_frac=y->rtc_irq_frac=frac[(i+3)%9];
        IO(x,0x301904)=IO(y,0x301904)=(uint8_t)((i%4)<<2);
        IO(x,0x301908)=IO(y,0x301908)=(i%3==0)?2:0;
        IO(x,0x30190c)=IO(y,0x30190c)=(i%3==1)?1:0;
        advance(a,b,(i%2)?100:17);
    }
    /* Each unsupported side effect, followed by a supported continuation. */
    for(int mode=0;mode<25;mode++) {
        for(int side=0;side<2;side++) {
            Emu *e=side?y:x;setup(e);
            if(mode==0) {e->halt_hard=false;e->wake_at=e->cycles+257;}
            if(mode==1) {e->pending_irq=65;e->pending_level=4;e->f_ie=1;}
            if(mode==2) {e->pending_irq=65;e->pending_level=4;e->f_ie=0;}
            if(mode==3) e->wake_req=true;
            if(mode==4) {e->halted=false;e->pc=0x02401000;}
            if(mode==5) e->flash_busy=3;
            if(mode==6) e->flash_autoselect=true;
            if(mode==7) e->flash_state=2;
            if(mode==8) e->ir_tx_ready_at=e->cycles+512;
            if(mode==9) {IO(e,e->dev.ir_base+5)=1;IO(e,e->dev.ir_base+2)=1;}
            if(mode>=10 && mode<16) {
                unsigned n=(unsigned)(mode-10);IO(e,0x300786+n*8)=1;
                IO(e,0x3007e0+n*2)=8;IO(e,0x300782+n*8)=3;
            }
            if(mode==16) {IO(e,0x301904)=1;IO(e,0x300277)=4;}
            if(mode==17) {IO(e,0x300270)=1;IO(e,0x300280)=1;}
            if(mode==18) {e->cycles=UINT64_MAX-511;e->last_tick=e->cycles;}
            if(mode==19) {e->rtc_mult=2;}
            if(mode==20) {e->pending_level=7;}
            if(mode==21) {IO(e,0x3002a6)=16;IO(e,0x3002a9)=16;}
            if(mode==22) {e->halted=false;e->pc=e->dev.dstram_base;mem_write16(e,e->pc,0x0080);}
            if(mode==23) {IO(e,0x301904)=1;e->rtc_irq_frac=nextafter(0.015625,0);e->rtc_sec_frac=nextafter(1,0);}
            if(mode==24) {e->halted=false;e->pc=e->dev.a0ram_base+0x1000;
                mem_write16(e,e->pc,0x1e00);e->trace_ring_pos=TRACE_RING_N-8;}
        }
        advance(a,b,1);
        for(int side=0;side<2;side++) {
            Emu *e=side?y:x;e->flash_busy=0;e->flash_autoselect=false;e->flash_state=0;
            e->rtc_mult=1;IO(e,e->dev.ir_base+5)=0;IO(e,e->dev.ir_base+2)=0;
            for(int n=0;n<6;n++) IO(e,0x300786+(unsigned)n*8)=0;
            memset(e->ioram+0x270,0,0x18);IO(e,0x3002a6)=IO(e,0x3002a9)=0;
            e->pending_irq=0;e->pending_level=0;e->wake_req=false;setup(e);
            mem_write16(e,e->dev.dstram_base+0x800,0x6c11);
            mem_write8(e,e->dev.a0ram_base+3,(uint8_t)mode);
            /* NOR mutation while halted must remain present across the jump. */
            e->rom[123]=(uint8_t)mode;e->flash_dirty=true;
        }
        advance(a,b,17);
    }
    /* Non-nearest arithmetic must retain the ordinary tick path. */
    CHECK(!fesetround(FE_UPWARD),"set rounding control");advance(a,b,17);
    CHECK(!fesetround(FE_TONEAREST),"restore rounding");
    CHECK(wear_button(a,0,1)==replay_reference_button(b,0,1),"button edge");advance(a,b,1);
    CHECK(wear_button(a,0,0)==replay_reference_button(b,0,0),"button release");advance(a,b,1);
    CHECK(wear_save(a,error)&&replay_reference_save(b,error),"durable saves");
    wear_close(a);replay_reference_close(b);a=wear_open(rom,save,error);b=replay_reference_open(rom,ref,error);
    CHECK(a&&b,"durable reopen");equal(a,b);advance(a,b,100);
    wear_close(a);replay_reference_close(b);CHECK(!unlink(save)&&!unlink(ref),"remove own saves");
}
static void bench(const char *rom,const char *save,const char *reference,unsigned seconds,int side) {
    WearBackend *a=side?replay_reference_open(rom,reference,error):wear_open(rom,save,error);CHECK(a,"benchmark open");
    Emu *e=(Emu *)(side?replay_reference_state(a):wear_state(a));
    if(e->cycles==0) setup(e);
    double start=now(),guest=e->emu_secs;
    for(unsigned i=0;i<seconds*10;i++) CHECK(side?replay_reference_advance_headless(a,100,error):wear_advance_headless(a,100,error),"benchmark advance");
    printf("BENCH side=%d seconds=%u wall=%.9f guest_delta=%.9f ticks=%"PRIu64" idle_calls=%"PRIu64"\n",side,seconds,now()-start,e->emu_secs-guest,ticks,idle_calls);
    CHECK(side?replay_reference_save(a,error):wear_save(a,error),"benchmark save");
    if(side) replay_reference_close(a);else wear_close(a);
}
int main(int argc,char **argv) {
    if(argc==6 && !strcmp(argv[1],"--bench")) {
        char rom[512],save[512],ref[512];snprintf(rom,sizeof rom,"%s/synthetic.rom",argv[4]);
        snprintf(save,sizeof save,"%s/candidate.state",argv[4]);snprintf(ref,sizeof ref,"%s/reference.state",argv[4]);
        if(!strcmp(argv[5],"init")) fixture(rom);
        bench(rom,save,ref,(unsigned)strtoul(argv[2],NULL,10),atoi(argv[3]));return 0;
    }
    char dir[]="/tmp/tamaemu-fast-idle-XXXXXX";CHECK(mkdtemp(dir),"owned temp directory");
    char rom[256],save[256],ref[256];snprintf(rom,sizeof rom,"%s/synthetic.rom",dir);
    snprintf(save,sizeof save,"%s/new.state",dir);snprintf(ref,sizeof ref,"%s/ref.state",dir);fixture(rom);
    if(argc==2 && !strcmp(argv[1],"--performance")) performance(rom,save);
    else {matrix(rom,save,ref);performance(rom,save);}
    CHECK(!unlink(rom)&&!rmdir(dir),"remove own fixture");printf("fast_idle_checks=%"PRIu64" PASS\n",checks);return 0;
}
