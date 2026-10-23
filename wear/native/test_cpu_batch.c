/* Firmware-free helper contract regression. cpu_step is intentionally the current
 * literal oracle; the independent frozen-oracle receipt lives outside this test.
 * Catches lying counts, partial refusals, stale fetches, split prefixes/slots,
 * missing side effects, and execution beyond a caller observation boundary. */
#include "emu.h"
#include <stdlib.h>
#include <string.h>
#include <limits.h>
#include <math.h>
#include <unistd.h>

#define ROM_N 0x20000u
#define CODE 0x1000u
static Emu a, b, before, na, nb;
static unsigned char ra[ROM_N], rb[ROM_N], snapshot[ROM_N];
static FILE *ea, *eb, *ta, *tb;
static const char *group;
static unsigned cases, calls, positive, multi, refused, literal, checks;
static unsigned pure_multi, stale_multi, residues, controls;
static unsigned (*run_helper)(Emu *, unsigned) = cpu_run_batch;
static const char *error;
#define CHECK(c) do { checks++; if (!(c)) { fprintf(stdout,"FAIL %s: %s (%s)\n",group,#c,error?error:""); exit(1); } } while (0)
static int reject(const char *why) { error=why; return 0; }
static void capture(FILE *f) { fflush(stderr); if(dup2(fileno(f),STDERR_FILENO)<0)exit(2); }
static long pos(FILE *f) { fflush(f); long p=ftell(f); if(p<0)exit(2); return p; }
static int streams(FILE *x, FILE *y, long ox, long oy) {
    fflush(x);fflush(y);
    long nx=lseek(fileno(x),0,SEEK_END)-ox, ny=lseek(fileno(y),0,SEEK_END)-oy;
    if(nx!=ny)return reject("diagnostic stream length");
    unsigned char px[1024],py[1024];
    for(long off=0;off<nx;) {
        size_t n=(size_t)(nx-off);if(n>sizeof px)n=sizeof px;
        if(pread(fileno(x),px,n,ox+off)!=(ssize_t)n || pread(fileno(y),py,n,oy+off)!=(ssize_t)n)exit(2);
        if(memcmp(px,py,n))return reject("diagnostic stream bytes");off+=(long)n;
    }
    /* dup2 and pread share descriptors; restore FILE write positions. */
    if(fseek(x,0,SEEK_END)||fseek(y,0,SEEK_END))exit(2);
    return 1;
}
static int normalize(Emu *out,const Emu *e,unsigned char *rom,FILE *trace) {
    memcpy(out,e,sizeof *out);
    if(e->rom!=rom || (e->tracef && e->tracef!=trace) || e->lcd.logf ||
       e->link || e->nfc_peer || e->nfc_vpeer)return reject("unexpected owned pointer");
    out->rom=NULL;out->tracef=e->tracef?(FILE *)(uintptr_t)1:NULL;
    return 1;
}
static int equal(void) {
    if(!normalize(&na,&a,ra,ta)||!normalize(&nb,&b,rb,tb))return 0;
    if(memcmp(&na,&nb,sizeof na))return reject("full Emu mismatch");
    if(memcmp(ra,rb,ROM_N))return reject("NOR mismatch");
    return 1;
}
/* No helper entry gates copied into this oracle. Refusal and N literal units
 * are checked generically; directed cases separately demand specified behavior. */
static int contract(unsigned budget,unsigned *out) {
    memcpy(&before,&a,sizeof a);memcpy(snapshot,ra,ROM_N);
    long da=pos(ea),db=pos(eb),xa=pos(ta),xb=pos(tb);
    capture(ea);unsigned n=run_helper(&a,budget);calls++;*out=n;
    if(n>budget)return reject("over budget");
    if(!n) {
        refused++;
        if(memcmp(&before,&a,sizeof a)||memcmp(snapshot,ra,ROM_N))return reject("mutative refusal");
    } else {
        positive++;if(n>1)multi++;
        double hz=b.cmu.mclk_hz;
        for(unsigned i=0;i<n;i++) {
            if(i && (b.halted||b.stopped||b.pending_irq||b.wake_req||
                     b.cmu.mclk_hz!=hz||b.cycles-b.last_tick>=256))
                return reject("crossed literal observation boundary");
            capture(eb);cpu_step(&b);literal++;
        }
    }
    return equal() && streams(ea,eb,da,db) && streams(ta,tb,xa,xb);
}
static unsigned batch(unsigned budget) { unsigned n;CHECK(contract(budget,&n));return n; }
static void fallback(void) {
    long da=pos(ea),db=pos(eb),xa=pos(ta),xb=pos(tb);
    capture(ea);cpu_step(&a);capture(eb);cpu_step(&b);literal++;
    CHECK(equal());CHECK(streams(ea,eb,da,db));CHECK(streams(ta,tb,xa,xb));
}
static unsigned progress(unsigned budget) { unsigned n=batch(budget);if(!n)fallback();return n; }
#define SET(f,v) do {a.f=(v);b.f=(v);} while(0)
static void word(uint32_t p,uint16_t w) { a.a0ram[p]=b.a0ram[p]=(uint8_t)w;a.a0ram[p+1]=b.a0ram[p+1]=(uint8_t)(w>>8); }
static void begin(const char *name) {
    group=name;cases++;error=NULL;memset(&a,0,sizeof a);memset(ra,0xa5,sizeof ra);
    a.dev=(DeviceProfile){.rom_base=PS_ROM_BASE,.rom_size=ROM_N,
      .a0ram_base=0,.a0ram_size=A0RAM_MAX,.ivram_base=PS_IVRAM_BASE,.ivram_size=IVRAM_MAX,
      .dstram_base=PS_DSTRAM_BASE,.dstram_size=DSTRAM_MAX,
      .io_base=PS_IO_BASE,.io_size=IORAM_MAX,.io_end=PS_IO_END,
      .lcd_cmd_addr=PS_LCD_CMD_ADDR,.lcd_data_addr=PS_LCD_DATA_ADDR,.ir_base=PS_IR_BASE,
      .ttbr_reset=0x400,.osc3_hz=PS_OSC3_HZ,.name="synthetic",.title="synthetic"};
    CHECK(device_check(&a.dev,stdout));
    a.pc=CODE;a.sp=0x6000;a.ttbr=0x400;a.rtc_mult=1;
    a.cmu.osc3_hz=PS_OSC3_HZ;a.cmu.mclk_hz=PS_OSC3_HZ;
    a.trace_ring_pos=TRACE_RING_N-2;
    for(unsigned i=0;i<128;i++){uint32_t target=CODE+0x100;memcpy(a.a0ram+0x400+4*i,&target,4);}
    memcpy(&b,&a,sizeof b);memcpy(rb,ra,ROM_N);a.rom=ra;b.rom=rb;
}
static void budgets_and_ticks(void) {
    const unsigned budgets[]={0,1,2,3,16,255,256,10000000};
    for(unsigned stale=0;stale<2;stale++)for(unsigned k=0;k<8;k++) {
        begin(stale?"stale pending level":"NOP budget");SET(pending_level,stale?7:0);
        unsigned n=batch(budgets[k]);CHECK(n<=256);
        CHECK(a.pc==CODE+2*n&&a.cycles==n&&a.trace_ring_pos==TRACE_RING_N-2+(int)n);
        if(budgets[k]<=3)CHECK(n==budgets[k]);
        if(n>1){if(stale)stale_multi++;else pure_multi++;}
    }
    for(unsigned r=0;r<256;r++) {
        begin("all tick residues");SET(cycles,1024+r);SET(last_tick,1024);
        unsigned n=batch(10000000);CHECK(n>0&&n<=256-r);CHECK(a.cycles==1024+r+n);residues++;
    }
}
static void refusals(void) {
    for(unsigned k=0;k<26;k++) {
        begin("immutable refusal");
        switch(k) {
        case 0:SET(pending_irq,32);SET(pending_level,5);break;
        case 1:SET(halted,true);break;case 2:SET(stopped,true);break;
        case 3:SET(wake_req,true);break;case 4:SET(trace,true);a.tracef=ta;b.tracef=tb;break;
        case 5:SET(n_pc_hook,1);SET(pc_hook[0],CODE);SET(pc_hook_name[0],"test");break;
        case 6:SET(post_ir_trace,1);break;case 7:SET(call_nranges,1);break;
        case 8:SET(cycles,256);break;
        case 9:SET(cycles,UINT64_MAX-2);SET(last_tick,UINT64_MAX-100);break;
        case 10:SET(trace_ring_pos,UINT32_C(0x7ffffffe));break;
        case 11:SET(trace_ring_pos,UINT32_MAX);break;
        case 12:SET(io_log,true);break;case 13:SET(ir_log,true);break;
        case 14:SET(tone_log,true);break;case 15:SET(nfc_log,true);break;
        case 16:SET(nfc_log_rf,true);break;case 17:SET(btn_log,true);break;
        case 18:SET(watch_hi,0x100);break;case 19:SET(rwatch_n,1);break;
        case 20:SET(fwatch_hi,0x100);break;
        default:SET(pending_irq,32);SET(pending_level,5);SET(f_ie,k&1);SET(il,k&2?7:1);break;
        }
        if(k==10 || k==11) {
            /* These two former signed-counter refusals must now make real
             * multi-unit progress and match literal unsigned steps. */
            CHECK(batch(16)==16);
            CHECK(a.trace_ring_pos==(k==10?UINT32_C(0x8000000e):UINT32_C(15)));
            for(unsigned j=0;j<16;j++)
                CHECK(a.trace_ring[((k==10?8190u:8191u)+j)&TRACE_RING_M]==CODE+2*j);
            continue;
        }
        CHECK(batch(16)==0);
        if(k==4||k==5)fallback();
    }
}
static void vectors(void) {
    begin("add flags");word(CODE,0x2210);SET(r[0],0x7fffffff);SET(r[1],1);progress(1);
    CHECK(a.r[0]==0x80000000u&&a.f_n&&a.f_v&&!a.f_z&&!a.f_c);
    begin("ADC carry");word(CODE,0xb810);SET(r[0],0xffffffff);SET(f_c,1);progress(1);
    CHECK(a.r[0]==0&&a.f_z&&a.f_c);
    begin("register alias add");word(CODE,0x2200);SET(r[0],3);progress(1);CHECK(a.r[0]==6);
    begin("postincrement alias load");word(CODE,0x3100);SET(r[0],0x2000);SET(a0ram[0x2000],0x20);progress(1);CHECK(a.r[0]==0x24);
    begin("two prefixes atomic");word(CODE,0xc001);word(CODE+2,0xc002);word(CODE+4,0x6c30);
    unsigned n=progress(1);CHECK(n<=1&&a.r[0]==0x80083&&a.cycles==3&&a.pc==CODE+6);
    CHECK(a.trace_ring_pos==TRACE_RING_N-1&&a.trace_ring[TRACE_RING_N-2]==CODE+4);
    begin("delay slot atomic");word(CODE,0x1f04);word(CODE+2,0x6010);n=progress(1);
    CHECK(n<=1&&a.pc==CODE+8&&a.r[0]==1&&a.cycles==2&&a.trace_ring_pos==TRACE_RING_N);
    CHECK(a.trace_ring[TRACE_RING_N-2]==CODE&&a.trace_ring[TRACE_RING_N-1]==CODE+2);
    begin("three prefix stop");word(CODE,0xc000);word(CODE+2,0xc001);word(CODE+4,0xc002);progress(16);
    CHECK(a.stopped&&a.cycles==3&&a.pc==CODE+6&&a.ext_count==3);
    for(unsigned k=0;k<3;k++) {
        begin("halt sleep stop");word(CODE,k==0?0x0080:k==1?0x0040:0x0400);n=progress(16);CHECK(n<=1);
        if(k<2)CHECK(a.halted&&a.halt_hard&&a.cycles==1);else CHECK(a.stopped&&a.cycles==1);
    }
    begin("IRQ stack known");SET(pending_irq,32);SET(pending_level,5);SET(f_ie,1);SET(il,1);
    CHECK(batch(16)==0);fallback();CHECK(!a.pending_irq&&!a.f_ie&&a.il==5&&a.irq_taken[32]==1&&a.sp==0x5ff8);
    CHECK(mem_read32(&a,0x5ffc)==CODE&&a.pc==CODE+0x102);
    for(unsigned byte=0;byte<2;byte++) {
        begin("store rewrites next instruction");word(CODE,byte?0x3410:0x3810);word(CODE+2,0x6c10);
        SET(r[0],byte?0x70:0x6c70);SET(r[1],CODE+2);
        unsigned done=progress(2);if(done<2)progress(1);
        CHECK(a.r[0]==7&&a.a0ram[CODE+2]==0x70&&a.cycles==2);
    }
    begin("trace ring wraps");SET(trace_ring_pos,TRACE_RING_N-1);CHECK(batch(3)==3);CHECK(a.trace_ring_pos==TRACE_RING_N+2);
    for(unsigned r=254;r<256;r++)for(unsigned delay=0;delay<2;delay++) {
        begin("atomic operation crosses tick");SET(cycles,r);word(CODE,delay?0x1f04:0xc001);
        word(CODE+2,delay?0x6010:0xc002);word(CODE+4,0x6c30);n=progress(16);CHECK(n<=1&&a.cycles>=256);
    }
}
static void mmio(void) {
    const uint32_t addr[]={PS_IR_BASE+1,0x300270,0x301900,0x301b08};
    for(unsigned k=0;k<4;k++) {
        begin("MMIO side effect boundary");SET(r[1],addr[k]);SET(r[0],k==3?4:0xff);
        SET(ioram[0x281],0xff);SET(ioram[0x280],1);SET(ioram[0xb12],1);SET(cmu.unlocked,1);
        word(CODE,k==0?0x2410:k==3?0x3c10:0x3410);unsigned n=progress(16);
        if(k==0)CHECK(!(a.ioram[0xb12]&1));
        if(k==1)CHECK(a.pending_irq&&a.wake_req&&n<=1);
        if(k==2)CHECK(a.rtcirq_clears==1&&!a.ioram[0x1900]);
        if(k==3)CHECK(a.cmu.mclk_hz==32768.0&&n<=1);
    }
    begin("external old-clock tick");SET(cycles,255);SET(cmu.unlocked,1);SET(r[1],0x301b08);SET(r[0],4);word(CODE,0x3c10);
    double old=a.cmu.mclk_hz;unsigned n=progress(16);CHECK(n<=1&&a.cycles==256&&a.cmu.mclk_hz==32768.0);
    capture(ea);periph_tick_at(&a,256,old);capture(eb);periph_tick_at(&b,256,old);SET(last_tick,256);
    CHECK(equal());CHECK(fabs(a.emu_secs-256.0/PS_OSC3_HZ)<1e-15);
}
static unsigned zero_helper(Emu *e,unsigned max) { (void)e;(void)max;return 0; }
static unsigned wrong_helper(Emu *e,unsigned max) { unsigned n=cpu_run_batch(e,max);if(n)e->r[0]^=1;return n; }
static int required_progress(unsigned budget) { unsigned n;return contract(budget,&n)&&n>1; }
static void negative_controls(void) {
    begin("control always-zero rejected");run_helper=zero_helper;CHECK(!required_progress(3));controls++;
    begin("control wrong architecture rejected");run_helper=wrong_helper;unsigned n;
    CHECK(!contract(3,&n));CHECK(error&&strcmp(error,"full Emu mismatch")==0);controls++;
    begin("correct helper mandatory progress");run_helper=cpu_run_batch;CHECK(required_progress(3));
}
int main(void) {
    ea=tmpfile();eb=tmpfile();ta=tmpfile();tb=tmpfile();if(!ea||!eb||!ta||!tb)return 2;
    setvbuf(stderr,NULL,_IONBF,0);unsetenv("TAMAEMU_SLPWAKE");
    negative_controls();budgets_and_ticks();refusals();vectors();mmio();
    CHECK(pure_multi&&stale_multi&&residues==256&&controls==2&&cases<1000);
    CHECK(streams(ea,eb,0,0));CHECK(streams(ta,tb,0,0));
    printf("PASS cpu_batch cases=%u calls=%u positive=%u multi=%u refused=%u literal=%u assertions=%u residues=%u negative_controls=%u pure_multi=%u stale_multi=%u Emu=%zu NOR=%u\n",
      cases,calls,positive,multi,refused,literal,checks,residues,controls,pure_multi,stale_multi,sizeof a,ROM_N);
    fclose(ea);fclose(eb);fclose(ta);fclose(tb);return 0;
}
