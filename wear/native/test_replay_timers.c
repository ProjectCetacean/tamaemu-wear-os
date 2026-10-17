#include "emu.h"
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void replay_reference_periph_tick(Emu *,uint32_t);
void replay_instrumented_periph_tick(Emu *,uint32_t);
static uint64_t checks,cases,instrumented_calls;
static int measuring;
/* Only the separate production-periph object is compiler-instrumented. */
void __cyg_profile_func_enter(void *function,void *caller) {
    (void)function;(void)caller;if(measuring) instrumented_calls++;
}
void __cyg_profile_func_exit(void *function,void *caller) {(void)function;(void)caller;}
#define CHECK(c,label) do {if(!(c)) {fprintf(stderr,"FAIL: %s line %d case=%" PRIu64 "\n",label,__LINE__,cases);exit(1);} checks++;} while(0)
#define IO(e,a) ((e)->ioram[(a)-(e)->dev.io_base])
static uint32_t random_state=0xa6170c31;
static uint32_t random32(void) {random_state^=random_state<<13;random_state^=random_state>>17;random_state^=random_state<<5;return random_state;}
static Emu *fresh(void) {
    Emu *e=calloc(1,sizeof *e);CHECK(e!=NULL,"timer fixture allocation");
    e->dev=*device_find("idl");e->cmu.mclk_hz=200000000;e->rtc_mult=1;IO(e,0x301908)=2;
    return e;
}
static void setup(Emu *e,int x,uint16_t counter,uint16_t a,uint16_t b,uint8_t fired,unsigned div,unsigned residue,uint8_t flags) {
    const unsigned prescaler[]={1,2,4,16,64,256,1024,4096};
    uint32_t base=0x300780+(unsigned)x*8;
    e->t16[x]=(T16){.counter=counter,.a_fired=fired,.accum=residue%prescaler[div]};
    IO(e,base)=(uint8_t)a;IO(e,base+1)=(uint8_t)(a>>8);IO(e,base+2)=(uint8_t)b;IO(e,base+3)=(uint8_t)(b>>8);
    IO(e,base+6)=1;IO(e,0x3007e0+(unsigned)x*2)=(uint8_t)(8|div);
    IO(e,0x300282+(unsigned)x/2)=flags;IO(e,0x300272+(unsigned)x/2)=0xff;
    IO(e,0x300266+(unsigned)x/2)=0x43;e->t16_fires[x]=UINT64_MAX-3;
}
static void compare_case(Emu *a,Emu *b,uint16_t counter,uint16_t ca,uint16_t cb,uint8_t fired,unsigned div,unsigned residue,uint32_t cycles) {
    int x=(int)(cases%6);uint8_t flags=(uint8_t)(cases*37);
    /* Disable previously selected channels so cases do not mask one another. */
    for(int i=0;i<6;i++) IO(a,0x300786+(unsigned)i*8)=IO(b,0x300786+(unsigned)i*8)=0;
    setup(a,x,counter,ca,cb,fired,div,residue,flags);setup(b,x,counter,ca,cb,fired,div,residue,flags);
    periph_tick(a,cycles);replay_reference_periph_tick(b,cycles);cases++;
    CHECK(!memcmp(a->t16,b->t16,sizeof a->t16),"all timer counters/one-shot latches/prescaler residues equal original clock loop");
    CHECK(!memcmp(a->t16_fires,b->t16_fires,sizeof a->t16_fires),"all B fires including uint64 wrap equal original");
    CHECK(!memcmp(a->ioram,b->ioram,sizeof a->ioram),"register/compare flag results equal original");
    CHECK(a->pending_irq==b->pending_irq && a->pending_level==b->pending_level && a->wake_req==b->wake_req,"IRQ priority and wake match original");
}
static void timer_equivalence(void) {
    Emu *a=fresh(),*b=fresh();
    /* Every counter value, including its uint16 wrap, against all compare orderings. */
    static const uint16_t compares[][2]={{0,0},{0,1},{1,0},{1,1},{2,1},{1,2},{32768,32767},{65535,1},{65535,65535},{1,65535},{65534,65535},{65535,65534}};
    for(uint32_t counter=0;counter<65536;counter++) for(size_t i=0;i<sizeof compares/sizeof *compares;i++) for(unsigned latch=0;latch<3;latch++)
        compare_case(a,b,(uint16_t)counter,compares[i][0],compares[i][1],(uint8_t)latch,0,0,(i&1)?31:1);
    /* Sweep each compare's complete 16-bit domain, with opposite-domain boundary values. */
    for(uint32_t c=0;c<65536;c++) {
        compare_case(a,b,65535,(uint16_t)c,65535,0,0,0,257);
        compare_case(a,b,65534,1,(uint16_t)c,1,0,0,2);
    }
    const unsigned prescaler[]={1,2,4,16,64,256,1024,4096};
    const uint32_t clocks[]={0,1,2,255,256,65535,65536,65537};
    for(unsigned d=0;d<8;d++) for(size_t n=0;n<sizeof clocks/sizeof *clocks;n++)
    for(size_t i=0;i<sizeof compares/sizeof *compares;i++) for(unsigned latch=0;latch<3;latch++) {
        uint32_t cycles=clocks[n]*prescaler[d];
        compare_case(a,b,(uint16_t)(i?65535:0),compares[i][0],compares[i][1],(uint8_t)latch,d,prescaler[d]-1,cycles);
    }
    /* Every legal prescaler residue around its carry boundary. */
    for(unsigned d=0;d<8;d++) for(unsigned residue=0;residue<prescaler[d];residue++)
    for(size_t i=0;i<sizeof compares/sizeof *compares;i++) {
        compare_case(a,b,(uint16_t)(i?65535:0),compares[i][0],compares[i][1],(uint8_t)(i%3),d,residue,prescaler[d]-residue-1);
        compare_case(a,b,(uint16_t)(i?65535:0),compares[i][0],compares[i][1],(uint8_t)(i%3),d,residue,prescaler[d]-residue);
    }
    for(int i=0;i<20000;i++) {
        unsigned d=random32()%8;uint32_t clocks_count=random32()%2048;
        compare_case(a,b,(uint16_t)random32(),(uint16_t)random32(),(uint16_t)random32(),(uint8_t)(random32()%3),d,random32(),clocks_count*prescaler[d]+random32()%prescaler[d]);
    }
    free(a);free(b);

    /* Long sequences retain all RTC, IRQ, tone, LCD and diagnostic state too. */
    a=fresh();b=fresh();
    for(int i=0;i<4000;i++) {
        if(i%13==0) for(int x=0;x<6;x++) {
            uint16_t ca=(uint16_t)random32(),cb=(uint16_t)random32();unsigned d=random32()%8;
            setup(a,x,(uint16_t)i,ca,cb,(uint8_t)(x%3),d,(unsigned)i,0);setup(b,x,(uint16_t)i,ca,cb,(uint8_t)(x%3),d,(unsigned)i,0);
        }
        uint32_t n=1+random32()%512;a->cycles+=n;b->cycles+=n;
        if(i%97==0) a->cmu.mclk_hz=b->cmu.mclk_hz=(i&1)?16384:18432000;
        periph_tick(a,n);replay_reference_periph_tick(b,n);
        CHECK(!memcmp(a,b,sizeof *a),"long sequence all peripheral/RTC/IRQ/tone/LCD bytes equal original");
    }
    free(a);free(b);printf("timer_cases=%" PRIu64 " checks=%" PRIu64 "\n",cases,checks);
}
static void timer_dispatch(void) {
    Emu *a=fresh(),*b=fresh();setup(a,0,0,1,1,0,0,0,0);setup(b,0,0,1,1,0,0,0,0);
    measuring=1;replay_instrumented_periph_tick(a,65536);measuring=0;
    replay_reference_periph_tick(b,65536);
    CHECK(!memcmp(a,b,sizeof *a),"instrumented production timer remains equivalent");
    printf("timer_clocks=65536 instrumented_native_calls=%" PRIu64 "\n",instrumented_calls);
    CHECK(instrumented_calls<100,"timer replay aggregates repeated compare events without per-clock calls");free(a);free(b);
}
int main(int argc,char **argv) {
    if(argc==2 && !strcmp(argv[1],"--dispatch")) timer_dispatch();else timer_equivalence();return 0;
}
