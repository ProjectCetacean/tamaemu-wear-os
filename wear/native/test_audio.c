#define _DARWIN_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
static unsigned checks;
#define CHECK(c,m) do {if(!(c)){fprintf(stderr,"FAIL: %s\n",m);exit(1);}checks++;} while(0)
static char error[160],dir[]="native-audio-XXXXXX",rom[512];
static unsigned serial;
static uint32_t frame[PANEL_W*PANEL_H];
static int16_t pcm[9600];
static WearBackend *fresh(void) {
    char save[512];snprintf(save,sizeof save,"%s/pet-%u.state",dir,serial++);
    WearBackend *b=wear_open(rom,save,error);CHECK(b!=NULL,"synthetic audio backend opens");
    Emu *e=(Emu *)wear_state(b);e->halted=true;e->halt_hard=true;e->cmu.mclk_hz=48000;
    return b;
}
static void step(WearBackend *b,int ms) {CHECK(wear_advance(b,ms,frame,PANEL_W*PANEL_H,error),"rendered audio step");}
static void timer(Emu *e,int enabled) {
    periph_write8(e,0x300780,24);periph_write8(e,0x300781,0);
    periph_write8(e,0x300782,47);periph_write8(e,0x300783,0);
    periph_write8(e,0x3007e0,8);periph_write8(e,0x300786,enabled?5:0);
}
static void equal(WearBackend *a,WearBackend *b,const char *why) {
    uint8_t x[32],y[32];wear_state_digest(a,x);wear_state_digest(b,y);CHECK(!memcmp(x,y,32),why);
}
static void program(Emu *e,const uint16_t *words,size_t n) {
    for(size_t i=0;i<n;i++){e->rom[0x400100+2*i]=(uint8_t)words[i];e->rom[0x400101+2*i]=(uint8_t)(words[i]>>8);}
    e->halted=false;e->pc=0x02400100;
}
static void frequency_and_edges(void) {
    WearBackend *b=fresh();Emu *e=(Emu *)wear_state(b);timer(e,1);
    CHECK(wear_audio_set_enabled(b,1),"enable original tone output");step(b,20);
    size_t n=wear_audio_drain(b,pcm,9600);CHECK(n==1024,"20ms request includes only legitimate 256-cycle peripheral overshoot");
    int crosses=0;for(size_t i=0;i<n;i++){CHECK(abs(pcm[i])==4500,"upstream square-wave amplitude");if(i&&pcm[i]!=pcm[i-1])crosses++;}
    CHECK(crosses>=41&&crosses<=43,"1kHz register tone has 48-sample period at 48kHz");
    periph_write8(e,0x300786,0);step(b,8);n=wear_audio_drain(b,pcm,9600);
    CHECK(n==512,"off edge preserves elapsed PCM duration");for(size_t i=0;i<n;i++)CHECK(pcm[i]==0,"timer off is silence");
    CHECK(wear_audio_drain(b,pcm,9600)==0,"drain consumes detached PCM once");wear_close(b);
}
static void high_clock_tone(void) {
    WearBackend *b=fresh();Emu *e=(Emu *)wear_state(b);e->cmu.mclk_hz=18432000;timer(e,1);
    periph_write8(e,0x300782,255);periph_write8(e,0x300783,71);
    CHECK(wear_audio_set_enabled(b,1),"enable high-clock 1kHz tone");step(b,20);
    size_t n=wear_audio_drain(b,pcm,9600);CHECK(n>=960&&n<=961,"high-clock sample duration uses clock-correct seconds");
    int crosses=0;for(size_t i=1;i<n;i++)if(pcm[i]!=pcm[i-1])crosses++;
    CHECK(crosses>=38&&crosses<=41,"18.432MHz timer divider produces original 1kHz pitch");wear_close(b);
}
static void short_edges_in_one_slice(void) {
    WearBackend *b=fresh();Emu *e=(Emu *)wear_state(b);timer(e,0);
    uint16_t words[516]={0};words[128]=0x3401;words[385]=0x3402;words[515]=0x0080;
    e->r[0]=0x300786;e->r[1]=5;e->r[2]=0;program(e,words,516);
    CHECK(wear_audio_set_enabled(b,1),"enable edge sequence");step(b,30);
    size_t n=wear_audio_drain(b,pcm,9600);CHECK(n>1000,"edge sequence yields timed audio");
    for(int i=0;i<128;i++)CHECK(pcm[i]==0,"initial silence retained");
    for(int i=132;i<384;i++)CHECK(abs(pcm[i])==4500,"short on edge survives off edge in same advance");
    for(size_t i=390;i<n;i++)CHECK(pcm[i]==0,"same-slice off edge stops sound");wear_close(b);
}
static void clock_segments(void) {
    WearBackend *b=fresh();Emu *e=(Emu *)wear_state(b);timer(e,1);
    periph_write32(e,0x301b24,0x96);
    uint16_t words[260]={0};words[128]=0x3c34;words[257]=0x3c35;words[259]=0x0080;
    e->r[3]=0x301b08;e->r[4]=4;e->r[5]=0x1004;program(e,words,260);
    CHECK(wear_audio_set_enabled(b,1),"enable clock transition tone");step(b,30);
    size_t n=wear_audio_drain(b,pcm,9600);
    long expected=(long)floor(129.0+129.0*48000/32768+(e->cycles-258)*48000/16384);
    CHECK(labs((long)n-expected)<=1,"clock segments convert cycles at their own rates");
    CHECK(e->cmu.mclk_hz==16384&&e->tone_freq==1000,"audio observes clock change without rewriting guest tone cache");
    int crosses=0;for(size_t i=400;i<n;i++)if(pcm[i]!=pcm[i-1])crosses++;
    double periods=(double)(n-400)*2* (16384.0/48)/48000;
    CHECK(fabs(crosses-periods)<2,"ongoing timer pitch follows low-clock segment");wear_close(b);
}
static void partition_and_overflow(void) {
    WearBackend *a=fresh(),*b=fresh();timer((Emu *)wear_state(a),1);timer((Emu *)wear_state(b),1);
    ((Emu *)wear_state(a))->cmu.mclk_hz=((Emu *)wear_state(b))->cmu.mclk_hz=18432000;
    periph_write8((Emu *)wear_state(a),0x300782,255);periph_write8((Emu *)wear_state(a),0x300783,71);
    periph_write8((Emu *)wear_state(b),0x300782,255);periph_write8((Emu *)wear_state(b),0x300783,71);
    CHECK(wear_audio_set_enabled(a,1)&&wear_audio_set_enabled(b,1),"enable partition pair");
    int16_t x[25000],y[25000];size_t nx=0,ny=0;
    for(int i=0;i<5;i++){step(a,100);nx+=wear_audio_drain(a,x+nx,9600);}
    for(int i=0;i<100;i++){step(b,i%2?9:1);ny+=wear_audio_drain(b,y+ny,9600);}
    CHECK(nx==ny&&!memcmp(x,y,nx*sizeof *x),"slice partitioning preserves every PCM sample and phase");CHECK(wear_state(a)->cycles==wear_state(b)->cycles && wear_state(a)->emu_secs==wear_state(b)->emu_secs,"slice partitioning retains executed guest time and cycles");
    for(int i=0;i<4;i++)step(a,100);
    periph_write8((Emu *)wear_state(a),0x300786,0);step(a,100);
    size_t n=wear_audio_drain(a,pcm,9600);CHECK(n==9600,"overflow remains bounded to newest 200ms");
    for(size_t i=n-4700;i<n;i++)CHECK(pcm[i]==0,"overflow drops oldest audio rather than latest silence");
    CHECK(wear_audio_drain(a,pcm,9600)==0,"overflow drain exhausts queue");wear_close(a);wear_close(b);
}
static void low_clock_work_bound(void) {
    WearBackend *b=fresh();Emu *e=(Emu *)wear_state(b);e->cmu.mclk_hz=128;
    periph_write8(e,0x300780,1);periph_write8(e,0x300782,2);
    periph_write8(e,0x3007e0,8);periph_write8(e,0x300786,5);
    CHECK(wear_audio_set_enabled(b,1),"enable bounded slow-clock output");step(b,1);
    size_t n=wear_audio_drain(b,pcm,9600);CHECK(n==9600,"one extreme slow-clock tick cannot exceed fixed PCM capacity");
    /* 256 cycles / 128 Hz = 96000 samples. Drop 86400, so first retained
     * sample phase is 86401*(128/3)/48000 mod 1 = .800888..., negative. */
    CHECK(pcm[0]==-4500,"discarded long segment advances noninteger oscillator phase correctly");wear_close(b);
}
static void suppression_and_validation(void) {
    WearBackend *b=fresh();timer((Emu *)wear_state(b),1);
    step(b,10);CHECK(wear_audio_drain(b,pcm,9600)==0,"audio defaults disabled");
    CHECK(wear_audio_set_enabled(b,1),"enable suppression fixture");step(b,10);
    CHECK(wear_audio_set_enabled(b,1),"repeated enable is idempotent");CHECK(wear_audio_drain(b,pcm,1)==1,"idempotent enable preserves queued PCM");
    CHECK(wear_audio_drain(b,NULL,1)==0,"null output rejected without consuming queue");
    CHECK(wear_audio_drain(b,pcm,0)==0,"zero capacity consumes nothing");
    CHECK(!wear_audio_set_enabled(b,2),"nonboolean audio state rejected");
    CHECK(wear_audio_drain(b,pcm,9600)>0,"rejected calls preserve queued samples");
    step(b,0);CHECK(wear_audio_drain(b,pcm,9600)==0,"zero-time render produces no samples");
    CHECK(!wear_advance(b,-1,frame,PANEL_W*PANEL_H,error),"negative audio step rejected");
    CHECK(wear_audio_drain(b,pcm,9600)==0,"invalid render creates no audio");
    step(b,10);CHECK(wear_advance_headless(b,100,error),"headless replay succeeds");CHECK(wear_audio_drain(b,pcm,9600)==0,"headless clears queued audio and creates no historical PCM");
    step(b,0);CHECK(wear_audio_drain(b,pcm,9600)==0,"zero-time rendering after catch-up remains silent");
    step(b,1);CHECK(wear_audio_drain(b,pcm,9600)<=768,"resumed output contains only new execution");
    step(b,10);CHECK(wear_audio_set_enabled(b,0),"disable output");CHECK(wear_audio_drain(b,pcm,9600)==0,"disable discards pending audio");
    CHECK(!wear_audio_set_enabled(NULL,1)&&wear_audio_drain(NULL,pcm,9600)==0,"null native audio handles fail safely");wear_close(b);
}
static void exact_state_and_restore(void) {
    WearBackend *a=fresh(),*b=fresh();Emu *ea=(Emu *)wear_state(a),*eb=(Emu *)wear_state(b);
    timer(ea,1);timer(eb,1);ea->rom[33]^=1;eb->rom[33]^=1;
    uint64_t life[8]={1,12345,500,2,250,0,9,0};CHECK(wear_write_life(a,life,error)&&wear_write_life(b,life,error),"audio pair life metadata");
    CHECK(wear_audio_set_enabled(a,1),"enable equivalence pair");
    const int ms[]={1,7,33,100,0,12,99,2};const double hz[]={48000,32768,16384,48000,48000,32768,16384,18432000};
    for(int i=0;i<8;i++) {
        ea->cmu.mclk_hz=eb->cmu.mclk_hz=hz[i];step(a,ms[i]);step(b,ms[i]);
        wear_audio_drain(a,pcm,9600);equal(a,b,"audio on/off exact guest NOR deadline and life equivalence");
    }
    CHECK(wear_save(a,error)&&wear_save(b,error),"equivalent states save");
    char save[512];snprintf(save,sizeof save,"%s/pet-%u.state",dir,serial-2);
    WearBackend *c=wear_open(rom,save,error);CHECK(c!=NULL,"saved tone ring reopens");equal(a,c,"restore preserves guest while runtime PCM is absent");
    CHECK(wear_audio_set_enabled(c,1),"restored audio enable");CHECK(wear_audio_drain(c,pcm,9600)==0,"restore does not replay persisted tone history");
    step(c,0);CHECK(wear_audio_drain(c,pcm,9600)==0,"restored zero-time frame is silent");step(a,11);step(c,11);equal(a,c,"audio continuation retains full canonical state");
    wear_close(a);wear_close(b);wear_close(c);
}
/* N1: a clock-changing store itself still runs at the previous Hz.
 * This one-cycle program gives an analytic integral independent of RTC/audio. */
static WearBackend *clock_loop(int mode) {
    WearBackend *b=fresh();Emu *e=(Emu *)wear_state(b);
    periph_write32(e,0x301b24,0x96);periph_write32(e,0x301b08,mode==2?0x1004:4);
    uint16_t words[256]={0};words[0]=0x3c34;words[128]=0x3c35;words[255]=0x0686;
    e->r[3]=0x301b08;e->r[4]=mode==1?0x1004:4;e->r[5]=mode==2?0x1004:4;e->r[6]=0x02400100;
    program(e,words,256);timer(e,1);return b;
}
static double clock_integral(uint64_t cycles,int mode) {
    if(!cycles)return 0;
    double first=mode==2?16384:32768,next=mode==1?16384:32768;
    uint64_t rest=cycles-1,tail=rest%256;
    return 1/first+(rest/256)*(128/next+128/first)
        +(tail<128?tail/next:128/next+(tail-128)/first);
}
static void clock_time_and_pacing(int mode) {
    WearBackend *a=clock_loop(mode),*b=clock_loop(mode),*c=clock_loop(mode);
    CHECK(wear_audio_set_enabled(a,1),"clock integral audio enabled");
    const int ms[]={1,10,16,33,1000,0,1,7,100,10,1,1};
    double requested=0;long total=0;uint64_t sample_hash=14695981039346656037ULL;
    for(size_t i=0;i<sizeof ms/sizeof *ms;i++) {
        step(a,ms[i]);step(b,ms[i]);CHECK(wear_advance_headless(c,ms[i],error),"clock integral headless step");
        const Emu *e=wear_state(a);double expected=clock_integral(e->cycles,mode);
        requested+=(ms[i]>100?100:ms[i])/1000.0;
        CHECK(fabs(e->emu_secs-expected)<1e-10,"N1 guest time equals pre-instruction clock integral");
        CHECK(expected-requested>=-1e-10 && expected-requested<=256.0/16384+1e-10,"N1 one cumulative deadline quantum, no per-call credit");
        size_t n=wear_audio_drain(a,pcm,9600);CHECK(n<9600,"clock integral has no ring discard");total+=(long)n;
        CHECK(labs(total-(long)floor(expected*48000+1e-7))<=1,"N1 PCM retains independent segment time");
        for(size_t j=0;j<n;j++){sample_hash^=(uint16_t)pcm[j];sample_hash*=1099511628211ULL;}
        equal(a,b,"N1 source audio on/off canonical twins");equal(a,c,"N1 headless canonical twin");
    }
    uint8_t before[32],after[32];wear_state_digest(a,before);
    CHECK(!wear_advance(a,-1,frame,PANEL_W*PANEL_H,error),"N1 negative request rejected");
    wear_state_digest(a,after);CHECK(!memcmp(before,after,32),"N1 rejection preserves carried deadline and guest");
    printf("N1_CLOCK mode=%d cycles=%llu seconds=%.12f frames=%ld pcm_hash=%016llx state=",mode,
        (unsigned long long)wear_state(a)->cycles,wear_state(a)->emu_secs,total,(unsigned long long)sample_hash);
    for(size_t i=0;i<sizeof before;i++)printf("%02x",before[i]);puts("");
    wear_close(a);wear_close(b);wear_close(c);
}
static void clock_boundary(unsigned at,int idle) {
    unsigned saved_serial=serial;WearBackend *a=fresh();Emu *e=(Emu *)wear_state(a);
    double old_hz=at==256?32768:16384;
    periph_write32(e,0x301b24,0x96);periph_write32(e,0x301b08,at==256?4:0x1004);
    uint16_t words[257]={0};unsigned store=idle?0:at-1;words[store]=0x3c34;words[store+1]=0x0080;
    e->r[3]=0x301b08;e->r[4]=at==256?0x1004:4;program(e,words,store+2);
    if(idle){e->halted=true;e->halt_hard=false;e->wake_at=256;}
    /* T1 prescaler /64 with compares beyond this test: count raw cycles. */
    periph_write8(e,0x300788,254);periph_write8(e,0x300789,255);
    periph_write8(e,0x30078a,255);periph_write8(e,0x30078b,255);
    periph_write8(e,0x3007e2,12);periph_write8(e,0x30078e,1);
    CHECK(wear_audio_set_enabled(a,1),"boundary audio enabled");step(a,1);
    uint64_t expected_cycles=idle?257:at;
    CHECK(e->cycles==expected_cycles && e->last_tick==e->cycles,"N1 stops at completed clock-change boundary");
    CHECK(fabs(e->emu_secs-expected_cycles/old_hz)<1e-12,"N1 boundary charges old Hz including changing instruction");
    CHECK(fabs(e->rtc_sec_frac-e->emu_secs)<1e-12,"N1 RTC shares corrected seconds");
    CHECK(fabs(e->rtc_irq_frac-fmod(e->emu_secs,1.0/64))<1e-12,"N1 cyclic RTC shares corrected seconds");
    CHECK(e->t16[1].counter==expected_cycles/64 && e->t16[1].accum==expected_cycles%64,"N1 T16 counts raw cycles across flush");
    CHECK(labs((long)wear_audio_drain(a,pcm,9600)-(long)floor(expected_cycles*48000/old_hz))<=1,"N1 boundary PCM duration");
    CHECK(wear_save(a,error),"N1 immediate post-change synthetic save");
    char save[512];snprintf(save,sizeof save,"%s/pet-%u.state",dir,saved_serial);
    WearBackend *b=wear_open(rom,save,error);CHECK(b!=NULL,"N1 post-change save restores");
    equal(a,b,"N1 post-change restore retains all guest and deadline state");
    CHECK(wear_audio_set_enabled(b,1),"N1 restored audio enabled");CHECK(wear_audio_drain(b,pcm,9600)==0,"N1 no persisted PCM carry");
    const int ms[]={0,1,7,33,100};
    for(size_t i=0;i<sizeof ms/sizeof *ms;i++) {
        step(a,ms[i]);step(b,ms[i]);equal(a,b,"N1 post-change restored continuation");
        size_t na=wear_audio_drain(a,pcm,9600),nb=wear_audio_drain(b,pcm,9600);
        CHECK(labs((long)na-(long)nb)<=1,"N1 restored PCM count within one runtime fraction");
        CHECK(e->t16[1].counter==e->cycles/64 && e->t16[1].accum==e->cycles%64,"N1 T16 raw cycles continue after idle");
    }
    wear_close(a);wear_close(b);
}
static void supplied_rom(const char *file) {
    char left[512],right[512];snprintf(left,sizeof left,"%s/rom-audio.state",dir);snprintf(right,sizeof right,"%s/rom-silent.state",dir);
    WearBackend *a=wear_open(file,left,error),*b=wear_open(file,right,error);CHECK(a&&b,"supplied local ROM pair opens");
    CHECK(wear_audio_set_enabled(a,1),"supplied ROM foreground audio enabled");
    unsigned read=wear_state(a)->tone_ev_w,events=0,nonzero=0,total=0;double low=20000,high=0;
    for(int i=0;i<180;i++) {
        if(i>=100 && i%10==0) {int key=((i-100)/10)%3;CHECK(wear_button(a,key,1)&&wear_button(b,key,1),"supplied ROM A/B/C down");}
        if(i>=100 && i%10==2) {int key=((i-102)/10)%3;CHECK(wear_button(a,key,0)&&wear_button(b,key,0),"supplied ROM A/B/C up");}
        step(a,100);step(b,100);size_t n=wear_audio_drain(a,pcm,9600);total+=(unsigned)n;
        for(size_t j=0;j<n;j++){CHECK(abs(pcm[j])<=4500,"supplied ROM PCM bounded");nonzero+=pcm[j]!=0;}
        const Emu *e=wear_state(a);CHECK(e->tone_ev_w-read<=TONE_EV_N,"supplied ROM tone observer retains event window");
        while(read!=e->tone_ev_w) {const struct ToneEv *ev=&e->tone_ev[read++%TONE_EV_N];
            if(ev->on&&ev->freq>=20&&ev->freq<=20000){events++;if(ev->freq<low)low=ev->freq;if(ev->freq>high)high=ev->freq;}}
        if(i%30==0)equal(a,b,"supplied ROM audio on/off exact guest NOR pacing life");
    }
    equal(a,b,"supplied ROM final audio on/off canonical equality");
    CHECK(wear_save(a,error)&&wear_save(b,error),"supplied ROM disposable checkpoints");
    uint8_t x[32],y[32];CHECK(wear_committed_payload_hash(a,x)&&wear_committed_payload_hash(b,y)&&!memcmp(x,y,32),"supplied ROM saved payloads byte-equivalent with and without audio");
    printf("SUPPLIED_ROM_PCM: total=%u nonzero=%u tone_on_events=%u pitch_range_hz=%.3f..%.3f audible_sample_seconds=%.6f guest_seconds=%.6f exact_state_and_save_equal=true\n",total,nonzero,events,high?low:0,high,nonzero/48000.0,wear_state(a)->emu_secs);
    CHECK(nonzero>0&&events>0,"actual supplied ROM produces original T0 beep PCM");wear_close(a);wear_close(b);
}
int main(int argc,char **argv) {
    CHECK(mkdtemp(dir)!=NULL,"audio test directory");snprintf(rom,sizeof rom,"%s/synthetic.bin",dir);
    uint8_t *image=calloc(1,8388608);CHECK(image!=NULL,"audio ROM allocation");
    image[0x400001]=1;image[0x400002]=0x40;image[0x400003]=2;image[0x400100]=0x80;
    FILE *f=fopen(rom,"wb");CHECK(f&&fwrite(image,1,8388608,f)==8388608&&fclose(f)==0,"audio ROM fixture");free(image);
    frequency_and_edges();high_clock_tone();short_edges_in_one_slice();clock_segments();partition_and_overflow();low_clock_work_bound();suppression_and_validation();exact_state_and_restore();
    clock_time_and_pacing(0);clock_time_and_pacing(1);clock_time_and_pacing(2);
    clock_boundary(32,0);clock_boundary(256,0);clock_boundary(257,1);
    if(argc>1)supplied_rom(argv[1]);
    printf("PASS: %u native audio checks\n",checks);return 0;
}
