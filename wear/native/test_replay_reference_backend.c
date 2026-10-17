/* Test-only slow oracle, frozen from d269385 on 2026-10-16.
 * Keep the original execution loop independent of production optimizations.
 * Wire codec alone updated for upstream 79e19b6 RAM expansion and v4. */
#include "test_replay_reference_names.h"
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include "sha256.h"
#include <errno.h>
#include <fcntl.h>
#include <float.h>
#include <math.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

/* Stable representation across ARM64 and host ABIs. No raw structs, pointers,
 * compiler padding, host byte order, or native size_t are written to disk. */
_Static_assert(sizeof(int)==4 && sizeof(unsigned)==4, "32-bit integers required");
_Static_assert(sizeof(float)==4 && sizeof(double)==8 && FLT_RADIX==2 && DBL_MANT_DIG==53, "IEEE floats required");
#define AUDIO_N 9600
struct Audio {
    int enabled; unsigned read; uint64_t cycle;
    double clock_raw,hz,freq,phase,fraction;
    uint8_t on; size_t head,count; int16_t pcm[AUDIO_N];
};
struct WearBackend { struct Audio audio; Emu *e; char *save_path; uint8_t rom_id[32]; double deadline; uint64_t life[8]; uint8_t committed_hash[32]; int has_commit; };
enum Kind { U8,U16,U32,U64,I32,F32,F64,BOOL };
struct Field {size_t offset, count, stride; enum Kind kind; size_t size;};
#define FIELD(path, kind, type) {offsetof(Emu,path),sizeof(((Emu *)0)->path)/sizeof(type),sizeof(type),kind,sizeof(type)},
#define STRIDED(path,member,kind,type) {offsetof(Emu,path[0].member),sizeof(((Emu *)0)->path)/sizeof(((Emu *)0)->path[0]),sizeof(((Emu *)0)->path[0]),kind,sizeof(type)},
static const struct Field fields[]={
#include "state_fields.inc"
};
#undef FIELD
#undef STRIDED
#define HEADER_N 96
#define PACING_N 8
#define LIFE_N 64
/* v2 adds a deadline; v3 adds eight host slots. v4 expands download RAM
 * from 2K to 4K. Legacy wire counts remain fixed and restore zero-extends
 * the formerly unmapped upper RAM; host slot meanings are unchanged. */
_Static_assert(sizeof(((Emu *)0)->dstram)==4096,"v4 download RAM schema");
static void put_double(uint8_t *p,double value);
static double get_double(const uint8_t *p);
static void error_set(char error[160],const char *message) {if(error) snprintf(error,160,"%s",message);}
static size_t wire_count(const struct Field *f,uint64_t version) {
    return version<4 && f->offset==offsetof(Emu,dstram) ? 2048 : f->count;
}
static size_t state_size_for(uint64_t version) {
    size_t n=0;for(size_t i=0;i<sizeof fields/sizeof *fields;i++) n+=wire_count(&fields[i],version)*fields[i].size;return n;
}
static size_t state_size(void) {return state_size_for(4);}
static void put64(uint8_t *p,uint64_t n) {for(int i=0;i<8;i++) p[i]=(uint8_t)(n>>(i*8));}
static uint64_t get64(const uint8_t *p) {uint64_t n=0;for(int i=0;i<8;i++) n|=(uint64_t)p[i]<<(i*8);return n;}
static void put_double(uint8_t *p,double value) {uint64_t bits;memcpy(&bits,&value,8);put64(p,bits);}
static double get_double(const uint8_t *p) {uint64_t bits=get64(p);double value;memcpy(&value,&bits,8);return value;}
static void encode(const Emu *e,uint8_t *out) {
    for(size_t i=0;i<sizeof fields/sizeof *fields;i++) {
        const struct Field *f=&fields[i];
        for(size_t j=0;j<f->count;j++) {
            const uint8_t *src=(const uint8_t *)e+f->offset+j*f->stride;
            uint64_t v=0;
            /* memcpy into the matching width avoids both aliasing and endian assumptions. */
            if(f->size==1) v=*src;
            else if(f->size==2) {uint16_t x;memcpy(&x,src,2);v=x;}
            else if(f->size==4) {uint32_t x;memcpy(&x,src,4);v=x;}
            else memcpy(&v,src,8);
            for(size_t k=0;k<f->size;k++) *out++=(uint8_t)(v>>(k*8));
        }
    }
}
static int decode(Emu *e,const uint8_t *in,uint64_t version) {
    for(size_t i=0;i<sizeof fields/sizeof *fields;i++) {
        const struct Field *f=&fields[i];
        for(size_t j=0;j<wire_count(f,version);j++) {
            uint64_t v=0;for(size_t k=0;k<f->size;k++) v|=(uint64_t)*in++<<(k*8);
            if(f->kind==BOOL && v>1) return 0;
            uint8_t *dst=(uint8_t *)e+f->offset+j*f->stride;
            if(f->size==1) *dst=(uint8_t)v;
            else if(f->size==2) {uint16_t x=(uint16_t)v;memcpy(dst,&x,2);}
            else if(f->size==4) {uint32_t x=(uint32_t)v;memcpy(dst,&x,4);}
            else memcpy(dst,&v,8);
            if(f->kind==F32) {float x;memcpy(&x,dst,4);if(!isfinite(x)) return 0;}
            if(f->kind==F64) {double x;memcpy(&x,dst,8);if(!isfinite(x)) return 0;}
        }
    }
    /* Standalone format permits no executable diagnostics or external peers. */
    if(e->auto_link || e->trace || e->io_log || e->ir_log || e->lcd.log ||
       e->btn_log || e->tone_log || e->nfc_log || e->nfc_log_rf || e->n_pc_hook ||
       e->rwatch_n || e->call_nranges || e->post_ir_trace || e->btn_mask>7 ||
       e->core_id || e->ext_count<0 || e->ext_count>2 || e->pc_hist_n<0 ||
       e->pc_hist_n>96 || e->lcd.pix_n<0 || e->lcd.pix_n>2 ||
       e->lcd.argc<0 || e->lcd.argc>1000000000 || e->lcd.xs<0 || e->lcd.xe<0 ||
       e->lcd.ys<0 || e->lcd.ye<0 || e->lcd.cx<0 || e->lcd.cy<0 ||
       e->lcd.xs>65535 || e->lcd.xe>65535 || e->lcd.ys>65535 || e->lcd.ye>65535 ||
       e->lcd.cx>65536 || e->lcd.cy>65536 || e->lcd.gp_n<0 || e->lcd.gp_n>16 ||
       e->lcd.gn_n<0 || e->lcd.gn_n>16 || e->lcd.c0_n<0 || e->lcd.c0_n>2 ||
       e->lcd.c2_n<0 || e->lcd.c2_n>1 || e->lcd.c5_n<0 || e->lcd.c5_n>1 ||
       e->lcd.c6_n<0 || e->lcd.c6_n>1 || e->flash_state<0 || e->flash_state>6 ||
       e->last_tick>e->cycles || e->cycles-e->last_tick>512 ||
       e->cmu.osc3_hz!=e->dev.osc3_hz || e->cmu.mclk_hz<0 || e->cmu.mclk_hz>200000000 ||
       e->emu_secs<0 || e->rtc_mult!=1 || e->stopped) return 0;
    return 1;
}
static int life_valid(const uint64_t life[8]) {
    for(int i=0;i<8;i++) if(life[i]>INT64_MAX) return 0;
    if(life[0]==0) {for(int i=1;i<8;i++) if(life[i]!=0) return 0;return 1;}
    return life[0]==1 && life[1]>0 && life[5]<=1 && life[6]>=1 && life[7]<=1;
}
int wear_read_life(const WearBackend *b,uint64_t out[8]) {
    if(!b || !out) return 0;memcpy(out,b->life,sizeof b->life);return 1;
}
int wear_write_life(WearBackend *b,const uint64_t life[8],char error[160]) {
    if(!b || !life || !life_valid(life)) {error_set(error,"Invalid life metadata");return 0;}
    memcpy(b->life,life,sizeof b->life);return 1;
}
int wear_committed_payload_hash(const WearBackend *b,uint8_t out[32]) {
    if(!b || !out || !b->has_commit) return 0;memcpy(out,b->committed_hash,32);return 1;
}
static void encode_life(const WearBackend *b,uint8_t *out) {
    for(int i=0;i<8;i++) put64(out+8*i,b->life[i]);
}
static int read_exact(FILE *f,void *out,size_t n) {return fread(out,1,n,f)==n;}
static int restore(WearBackend *b,char error[160]) {
    FILE *f=fopen(b->save_path,"rb");
    if(!f) {if(errno==ENOENT) return 1;error_set(error,"Cannot read existing save");return 0;}
    uint8_t h[HEADER_N],digest[32]; size_t guest_n=state_size()+b->e->dev.rom_size;
    size_t n=guest_n;
    uint8_t *payload=malloc(guest_n+PACING_N+LIFE_N); Emu *candidate=calloc(1,sizeof *candidate);
    int ok=0;
    if(!payload || !candidate) {error_set(error,"Save restore allocation failed");goto done;}
    if(!read_exact(f,h,sizeof h) || memcmp(h,"TAMAWEAR",8) ||
       (get64(h+8)<1 || get64(h+8)>4)) {
        error_set(error,"Save is truncated or incompatible");goto done;
    }
    uint64_t version=get64(h+8);
    guest_n=state_size_for(version)+b->e->dev.rom_size;n=guest_n;
    if(version>=2) n+=PACING_N;
    if(version>=3) n+=LIFE_N;
    if(get64(h+16)!=n || memcmp(h+24,b->rom_id,32) || memcmp(h+88,"idl\0\0\0\0\0",8) ||
       !read_exact(f,payload,n) || fgetc(f)!=EOF || ferror(f)) {
        error_set(error,"Save is truncated, incompatible, or belongs to another ROM");goto done;
    }
    wear_sha256(payload,n,digest);
    if(memcmp(digest,h+56,32)) {error_set(error,"Save integrity check failed");goto done;}
    candidate->dev=b->e->dev;
    if(!decode(candidate,payload,version)) {error_set(error,"Save contains invalid emulator state");goto done;}
    /* Legacy files have no pacing state. Anchor at the saved guest time;
     * never reset the guest or infer suspended wall time. v2 retains exact
     * sub-quantum overshoot even across changing MCLKs and repeated saves. */
    double deadline=get64(h+8)==1 ? candidate->emu_secs : get_double(payload+guest_n);
    if(!isfinite(deadline) || deadline<0 || deadline>candidate->emu_secs ||
       candidate->emu_secs-deadline>1.0) {
        error_set(error,"Save contains invalid foreground pacing state");goto done;
    }
    uint64_t life[8]={0};
    if(version>=3) for(int i=0;i<8;i++) life[i]=get64(payload+guest_n+PACING_N+8*i);
    if(!life_valid(life)) {error_set(error,"Save contains invalid life metadata");goto done;}
    candidate->rom=b->e->rom;
    memcpy(candidate->rom,payload+state_size_for(version),candidate->dev.rom_size);
    free(b->e); b->e=candidate; b->deadline=deadline; candidate=NULL;
    memcpy(b->life,life,sizeof life);memcpy(b->committed_hash,h+56,32);b->has_commit=1;ok=1;
done:
    free(candidate);free(payload);fclose(f);return ok;
}
WearBackend *wear_open(const char *rom_path,const char *save_path,char error[160]) {
    error_set(error,"");
    if(!rom_path || !save_path || !*save_path) {error_set(error,"Missing local firmware or save destination");return NULL;}
    WearBackend *b=calloc(1,sizeof *b); FILE *f=NULL;
    if(!b) {error_set(error,"Emulator allocation failed");return NULL;}
    b->e=calloc(1,sizeof *b->e); b->save_path=strdup(save_path);
    if(!b->e || !b->save_path) {error_set(error,"Emulator allocation failed");goto fail;}
    b->e->dev=*device_find("idl"); b->e->cmu.osc3_hz=b->e->dev.osc3_hz; b->e->rtc_mult=1;
    b->e->rom=malloc(b->e->dev.rom_size);
    if(!b->e->rom) {error_set(error,"Firmware allocation failed");goto fail;}
    f=fopen(rom_path,"rb");
    if(!f || !read_exact(f,b->e->rom,b->e->dev.rom_size) || fgetc(f)!=EOF || ferror(f)) {
        error_set(error,"Cannot read local firmware or firmware size is incorrect");goto fail;
    }
    fclose(f);f=NULL;
    wear_sha256(b->e->rom,b->e->dev.rom_size,b->rom_id);
    /* This first checkpoint is explicitly the user's iD L image. Other maps
     * need a profile selector and their own boot evidence before support. */
    uint32_t vector=mem_read32(b->e,b->e->dev.ttbr_reset);
    if((vector&1) || vector-b->e->dev.rom_base>=b->e->dev.rom_size) {
        error_set(error,"Firmware reset vector is invalid for iD L");goto fail;
    }
    cpu_reset(b->e);
    if(!restore(b,error)) goto fail;
    return b;
fail:
    if(f) fclose(f);wear_close(b);return NULL;
}
void wear_close(WearBackend *b) {
    if(!b) return;if(b->e) {free(b->e->rom);free(b->e);}free(b->save_path);free(b);
}
static double audio_hz(const Emu *e) {
    double hz=e->cmu.mclk_hz;
    return isfinite(hz) && hz>0 ? hz : e->dev.osc3_hz;
}
static double audio_pitch(const Emu *e,double hz) {
    static const unsigned divisor[8]={1,2,4,16,64,256,1024,4096};
    const uint8_t *io=e->ioram;
    unsigned base=0x300780-e->dev.io_base;
    unsigned crb=io[base+2]|((unsigned)io[base+3]<<8);
    return hz/divisor[io[0x3007e0-e->dev.io_base]&7]/(crb+1.0);
}
static void audio_resync(WearBackend *b) {
    struct Audio *a=&b->audio;const Emu *e=b->e;
    a->read=e->tone_ev_w;a->cycle=e->cycles;a->clock_raw=e->cmu.mclk_hz;
    a->hz=audio_hz(e);a->freq=audio_pitch(e,a->hz);a->on=e->tone_on;
    a->phase=0;a->fraction=0;a->head=0;a->count=0;
}
int wear_audio_set_enabled(WearBackend *b,int enabled) {
    if(!b || (enabled!=0 && enabled!=1)) return 0;
    if(b->audio.enabled!=enabled) {b->audio.enabled=enabled;audio_resync(b);}
    return 1;
}
size_t wear_audio_drain(WearBackend *b,int16_t *out,size_t capacity) {
    if(!b || (!out && capacity)) return 0;
    struct Audio *a=&b->audio;size_t n=capacity<a->count?capacity:a->count;
    for(size_t i=0;i<n;i++) out[i]=a->pcm[(a->head+i)%AUDIO_N];
    a->head=(a->head+n)%AUDIO_N;a->count-=n;return n;
}
/* Segment clocks are observed during CPU execution, never reconstructed from
 * a frame's final MCLK. Audio owns no guest fields and cannot alter execution. */
static void audio_segment(struct Audio *a,uint64_t until) {
    if(until<=a->cycle) return;
    double samples=(double)(until-a->cycle)*48000/a->hz+a->fraction;
    if(!isfinite(samples)) {a->cycle=until;a->phase=0;a->fraction=0;a->head=0;a->count=0;return;}
    double whole=floor(samples+1e-9);a->fraction=samples-whole;
    if(a->fraction<0) a->fraction=0;
    a->cycle=until;
    int sounding=a->on && isfinite(a->freq) && a->freq>=20 && a->freq<=20000;
    /* A corrupt/extreme clock cannot allocate or loop in proportion to a long
     * interval. Keep only the newest bounded PCM, advancing phase over drops. */
    if(whole>AUDIO_N) {
        if(sounding) a->phase=fmod(a->phase+fmod(whole-AUDIO_N,48000/a->freq)*a->freq/48000,1.0);
        whole=AUDIO_N;a->head=0;a->count=0;
    }
    for(size_t i=0;i<(size_t)whole;i++) {
        int16_t sample=0;
        if(sounding) {a->phase+=a->freq/48000;if(a->phase>=1) a->phase-=1;sample=a->phase<0.5?4500:-4500;}
        if(a->count==AUDIO_N) {a->head=(a->head+1)%AUDIO_N;a->count--;}
        a->pcm[(a->head+a->count)%AUDIO_N]=sample;a->count++;
    }
}
static void audio_observe(WearBackend *b) {
    struct Audio *a=&b->audio;const Emu *e=b->e;
    if(e->cycles<a->cycle || e->tone_ev_w-a->read>TONE_EV_N) {audio_resync(b);return;}
    while(a->read!=e->tone_ev_w) {
        const struct ToneEv *ev=&e->tone_ev[a->read%TONE_EV_N];
        if(ev->cyc>e->cycles) {audio_resync(b);return;}
        audio_segment(a,ev->cyc);a->on=ev->on;a->freq=ev->freq;a->read++;
    }
    if(a->clock_raw!=e->cmu.mclk_hz) {
        audio_segment(a,e->cycles);a->clock_raw=e->cmu.mclk_hz;
        a->hz=audio_hz(e);a->freq=audio_pitch(e,a->hz);
    }
}
static int advance_execution(WearBackend *b,int ms,int audible,char error[160]) {
    if(ms>100) ms=100;
    Emu *e=b->e;double until=b->deadline+(double)ms/1000;
    /* Instructions and 100ms cap bound work even if a malformed ROM changes clocks. */
    unsigned steps=0;
    if(audible) audio_observe(b);
    while(e->emu_secs<until && !e->stopped && steps++<10000000) {
        cpu_step(e);
        if(audible && (e->tone_ev_w!=b->audio.read || e->cmu.mclk_hz!=b->audio.clock_raw)) audio_observe(b);
        if(e->cycles-e->last_tick>=256) {
            periph_tick(e,(uint32_t)(e->cycles-e->last_tick));e->last_tick=e->cycles;
            periph_buttons(e,e->btn_mask);
        }
    }
    if(e->stopped || steps>=10000000) {audio_resync(b);error_set(error,"Emulator stopped or exceeded foreground work limit");return 0;}
    /* Carry the peripheral tick overshoot into the next request instead of
     * rounding each frontend frame upward independently. Zero elapsed merely
     * renders; rejected requests never change this shared pacing deadline. */
    if(audible) audio_segment(&b->audio,e->cycles);
    b->deadline=until;return 1;
}
int wear_advance_headless(WearBackend *b,int ms,char error[160]) {
    if(!b || ms<0) {error_set(error,"Invalid elapsed time or backend");return 0;}
    int ok=advance_execution(b,ms,0,error);audio_resync(b);return ok;
}
int wear_advance(WearBackend *b,int ms,uint32_t *argb,size_t pixels,char error[160]) {
    if(!b || !argb || pixels!=PANEL_W*PANEL_H || ms<0) {error_set(error,"Invalid frame buffer or elapsed time");return 0;}
    if(!advance_execution(b,ms,ms>0 && b->audio.enabled,error)) return 0;
    Emu *e=b->e;
    /* Foreground presentation retains GRAM when firmware blanks its LCD.
     * All calls are serialized; restore this renderer flag immediately so CPU,
     * power registers, guest state and saved preferences remain unchanged. */
    bool stay_awake=e->stay_awake;e->stay_awake=true;
    int w,h;lcd_render(e,argb,&w,&h);e->stay_awake=stay_awake;return 1;
}
int wear_button(WearBackend *b,int button,int pressed) {
    if(!b || button<0 || button>2) return 0;
    uint8_t mask=b->e->btn_mask;
    if(pressed) mask|=(uint8_t)(1u<<button);else mask&=(uint8_t)~(1u<<button);
    periph_buttons(b->e,mask);return 1;
}
int wear_save(WearBackend *b,char error[160]) {
    if(!b || b->e->stopped) {error_set(error,"Cannot save stopped emulator");return 0;}
    size_t guest_n=state_size()+b->e->dev.rom_size, n=guest_n+PACING_N+LIFE_N;
    uint8_t *payload=malloc(n);size_t path_n=strlen(b->save_path)+16;
    char *temp=malloc(path_n);char *parent=strdup(b->save_path);int fd=-1,dir_fd=-1;FILE *f=NULL;int ok=0,created=0;
    if(!payload || !temp || !parent) {error_set(error,"Save allocation failed");goto done;}
    char *slash=strrchr(parent,'/');
    if(!slash) {free(parent);parent=strdup(".");if(!parent) {error_set(error,"Save allocation failed");goto done;}}
    else if(slash==parent) slash[1]='\0';else *slash='\0';
    int dir_flags=O_RDONLY;
#ifdef O_DIRECTORY
    dir_flags|=O_DIRECTORY;
#endif
    dir_fd=open(parent,dir_flags);
    if(dir_fd<0) {error_set(error,"Cannot open save parent directory");goto done;}
    encode(b->e,payload);memcpy(payload+state_size(),b->e->rom,b->e->dev.rom_size);
    put_double(payload+guest_n,b->deadline);encode_life(b,payload+guest_n+PACING_N);
    uint8_t h[HEADER_N]={0};memcpy(h,"TAMAWEAR",8);put64(h+8,4);put64(h+16,n);
    memcpy(h+24,b->rom_id,32);wear_sha256(payload,n,h+56);memcpy(h+88,"idl",3);
    snprintf(temp,path_n,"%s.XXXXXX",b->save_path);
    fd=mkstemp(temp);
    if(fd<0) {error_set(error,"Cannot create temporary save");goto done;}
    created=1;
    f=fdopen(fd,"wb");if(!f) {close(fd);fd=-1;error_set(error,"Cannot open temporary save");goto done;}
    int written=fwrite(h,1,sizeof h,f)==sizeof h && fwrite(payload,1,n,f)==n && fflush(f)==0 && fsync(fd)==0;
    int closed=fclose(f)==0;f=NULL;fd=-1;
    if(!written || !closed) {error_set(error,"Cannot finish temporary save");goto done;}
    if(rename(temp,b->save_path)!=0) {error_set(error,"Cannot replace save; previous save preserved");goto done;}
    if(fsync(dir_fd)!=0) {error_set(error,"Save replaced but directory durability is uncertain; retry required");goto done;}
    if(close(dir_fd)!=0) {dir_fd=-1;error_set(error,"Save replaced but directory close failed; retry required");goto done;}
    dir_fd=-1;memcpy(b->committed_hash,h+56,32);b->has_commit=1;ok=1;
done:
    if(f) fclose(f);
    if(temp && !ok && fd!=-1) close(fd);
    if(created && !ok) unlink(temp);
    if(dir_fd!=-1) close(dir_fd);
    free(parent);free(temp);free(payload);return ok;
}
const Emu *wear_state(const WearBackend *b) {return b?b->e:NULL;}
void wear_state_digest(const WearBackend *b,uint8_t out[32]) {
    size_t guest_n=state_size()+b->e->dev.rom_size,n=guest_n+PACING_N+LIFE_N;uint8_t *p=malloc(n);
    if(!p) {memset(out,0,32);return;}
    encode(b->e,p);memcpy(p+state_size(),b->e->rom,b->e->dev.rom_size);put_double(p+guest_n,b->deadline);encode_life(b,p+guest_n+PACING_N);wear_sha256(p,n,out);free(p);
}
