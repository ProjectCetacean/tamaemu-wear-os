/* Positive unsigned-counter tests only. Never execute a signed-boundary CPU.
 * Catches signed field regressions, dropped/misordered ring writes, conservative
 * wrap refusal, wrong diagnostic walks and changed v4 bytes/digest/restore. */
#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE 1
#include "backend.h"
#include "sha256.h"
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

_Static_assert(_Generic(((Emu *)0)->trace_ring_pos,uint32_t:1,default:0),"unsigned counter required before execution");
_Static_assert(sizeof(int)==sizeof(uint32_t),"legacy desktop counter width");
_Static_assert(_Alignof(int)==_Alignof(uint32_t),"legacy desktop counter alignment");
static unsigned checks,wraps,diagnostics,fixtures;
#define CHECK(x) do {checks++;if(!(x)){fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x);exit(1);}}while(0)
static Emu a,b;
#define CODE 0x1000u
static void setup(Emu *e) {
    memset(e,0,sizeof *e);e->dev=*device_find("idl");
    e->pc=CODE;e->sp=0x6000;e->rtc_mult=1;
    e->cmu.osc3_hz=e->cmu.mclk_hz=e->dev.osc3_hz;
}
static void wrap_vectors(void) {
    const uint32_t start[]={0x7ffffff8u,0xfffffff8u};
    const uint32_t end[]={0x80000008u,8u};
    for(unsigned v=0;v<2;v++) {
        setup(&a);a.trace_ring_pos=start[v];
        for(unsigned i=0;i<TRACE_RING_N;i++)a.trace_ring[i]=0xdeadbeefu;
        memcpy(&b,&a,sizeof b);
        CHECK(cpu_run_batch(&a,16)==16); /* mandatory multi, no fallback */
        for(unsigned j=0;j<16;j++)cpu_step(&b);
        CHECK(a.trace_ring_pos==end[v]);CHECK(b.trace_ring_pos==end[v]);
        CHECK(!memcmp(&a,&b,sizeof a));
        /* Both starts select slot 8184: eight tail slots then eight head slots. */
        for(unsigned i=0;i<TRACE_RING_N;i++) {
            uint32_t want=0xdeadbeefu;
            if(i>=8184)want=CODE+2*(i-8184);
            if(i<8)want=CODE+16+2*i;
            CHECK(a.trace_ring[i]==want);
        }
        CHECK(a.pc==CODE+32&&a.cycles==16);wraps++;
    }
}
static char *captured(Emu *e) {
    FILE *f=tmpfile();CHECK(f);fflush(stderr);int saved=dup(STDERR_FILENO);CHECK(saved>=0);
    CHECK(dup2(fileno(f),STDERR_FILENO)>=0);cpu_step(e);fflush(stderr);
    CHECK(dup2(saved,STDERR_FILENO)>=0);close(saved);
    CHECK(fseek(f,0,SEEK_END)==0);long n=ftell(f);CHECK(n>=0);rewind(f);
    char *out=calloc(1,(size_t)n+1);CHECK(out);CHECK(fread(out,1,(size_t)n,f)==(size_t)n);fclose(f);return out;
}
static void diagnostic_vectors(void) {
    const uint32_t positions[]={0u,1u,63u,0x80000000u,UINT32_MAX};
    const unsigned first[]={8128u,8129u,8191u,8128u,8127u};
    for(unsigned v=0;v<5;v++)for(unsigned hook=0;hook<2;hook++) {
        setup(&a);a.trace_ring_pos=positions[v];
        for(unsigned j=0;j<64;j++)a.trace_ring[(first[v]+j)%8192]=0x00100000u+j;
        if(hook){a.n_pc_hook=1;a.pc_hook[0]=CODE;a.pc_hook_ring[0]=1;a.pc_hook_name[0]="counter-test";}
        else a.pc=0x0f000000u;
        char *out=captured(&a),*p=strstr(out,hook?"path in, newest first":"last 64 instructions, oldest first");CHECK(p);
        for(unsigned j=0;j<64;j++) {
            char token[16];snprintf(token,sizeof token," %08x",0x00100000u+(hook?63-j:j));
            p=strstr(p,token);CHECK(p);p+=9;
        }
        if(!hook)CHECK(strstr(p,"jumped from 0010003f"));
        free(out);diagnostics++;
    }
}
static void le(uint8_t *p,uint64_t n,size_t width){for(size_t i=0;i<width;i++)p[i]=(uint8_t)(n>>(8*i));}
static void scalar(uint8_t **p,const void *src,size_t width) {
    uint64_t n=0;
    if(width==1)n=*(const uint8_t *)src;
    else if(width==2){uint16_t x;memcpy(&x,src,2);n=x;}
    else if(width==4){uint32_t x;memcpy(&x,src,4);n=x;}
    else memcpy(&n,src,8);
    le(*p,n,width);*p+=width;
}
/* Frozen I32-era schema, untouched. Only v4's documented 4096-byte download
 * RAM extension changes its old 2048 count, in its original field position.
 * Reads negative-era representations as bytes, never signed arithmetic. */
static size_t old_v4_guest(const Emu *e,uint8_t *out,size_t *trace_offset) {
    uint8_t *p=out;
#define FROZEN_FIELD(member,kind,type,count) do { \
    if(!strcmp(#member,"trace_ring_pos"))*trace_offset=(size_t)(p-out); \
    size_t n=!strcmp(#member,"dstram")?4096:(count); \
    for(size_t j=0;j<n;j++)scalar(&p,(const uint8_t *)&e->member+j*sizeof(type),sizeof(type)); \
}while(0);
#define FROZEN_STRIDED(member,sub,kind,type,count) do {for(size_t j=0;j<count;j++)scalar(&p,&e->member[j].sub,sizeof(type));}while(0);
#include "test_wear9_state_fields.inc"
#undef FROZEN_FIELD
#undef FROZEN_STRIDED
    CHECK((size_t)(p-out)==149781);memcpy(p,e->rom,8388608);return 8538389;
}
static void write_file(const char *path,const void *data,size_t n) {
    FILE *f=fopen(path,"wb");CHECK(f);CHECK(fwrite(data,1,n,f)==n);CHECK(fclose(f)==0);
}
static void wire_vectors(void) {
    char dir[]="trace-counter-XXXXXX";CHECK(mkdtemp(dir));
    char rom[256],save[256];snprintf(rom,sizeof rom,"%s/synthetic.bin",dir);snprintf(save,sizeof save,"%s/counter.sav",dir);
    uint8_t *image=calloc(1,8388608);CHECK(image);
    const DeviceProfile *dev=device_find("idl");CHECK(dev&&dev->rom_size==8388608);
    le(image+dev->ttbr_reset-dev->rom_base,dev->rom_base+0x1000,4);
    write_file(rom,image,8388608);uint8_t rom_hash[32];wear_sha256(image,8388608,rom_hash);free(image);
    const uint32_t values[]={0x7ffffff8u,0xfffffff0u,UINT32_MAX};
    const uint8_t bits[][4]={{0xf8,0xff,0xff,0x7f},{0xf0,0xff,0xff,0xff},{0xff,0xff,0xff,0xff}};
    const size_t payload_n=8538461,total=8538557;
    for(unsigned v=0;v<3;v++) {
        char error[160];WearBackend *backend=wear_open(rom,save,error);CHECK(backend);
        Emu *e=(Emu *)wear_state(backend);e->trace_ring_pos=values[v];
        /* Retain known data around the field to detect ordering/drop changes. */
        e->trace_ring[0]=0x12345678u;e->trace_ring[8191]=0x87654321u;
        uint8_t *old=calloc(1,total),*actual=malloc(total);CHECK(old&&actual);size_t offset=0;
        CHECK(old_v4_guest(e,old+96,&offset)==8538389);
        CHECK(!memcmp(old+96+offset,bits[v],4));
        /* New sessions/restored fixtures keep zero pacing and life here. */
        memcpy(old,"TAMAWEAR",8);le(old+8,4,8);le(old+16,payload_n,8);
        memcpy(old+24,rom_hash,32);wear_sha256(old+96,payload_n,old+56);memcpy(old+88,"idl",3);
        uint8_t digest[32];wear_state_digest(backend,digest);CHECK(!memcmp(digest,old+56,32));
        CHECK(wear_save(backend,error));FILE *f=fopen(save,"rb");CHECK(f);
        CHECK(fread(actual,1,total,f)==total&&fgetc(f)==EOF);fclose(f);CHECK(!memcmp(actual,old,total));wear_close(backend);
        /* Load an independently written old-I32 v4 bit fixture, not self-roundtrip. */
        write_file(save,old,total);backend=wear_open(rom,save,error);CHECK(backend);
        e=(Emu *)wear_state(backend);CHECK(e->trace_ring_pos==values[v]);
        CHECK(e->trace_ring[0]==0x12345678u&&e->trace_ring[8191]==0x87654321u);
        wear_state_digest(backend,digest);CHECK(!memcmp(digest,old+56,32));
        CHECK(wear_save(backend,error));wear_close(backend);
        f=fopen(save,"rb");CHECK(f&&fread(actual,1,total,f)==total&&fgetc(f)==EOF);fclose(f);CHECK(!memcmp(actual,old,total));
        free(old);free(actual);fixtures++;
    }
    CHECK(unlink(save)==0);CHECK(unlink(rom)==0);CHECK(rmdir(dir)==0);
}
int main(void) {
    unsetenv("TAMAEMU_SLPWAKE");wrap_vectors();diagnostic_vectors();wire_vectors();
    printf("PASS trace_counter assertions=%u wrap_vectors=%u diagnostic_vectors=%u old_v4_fixtures=%u Emu=%zu offset=%zu align=%zu\n",checks,wraps,diagnostics,fixtures,sizeof(Emu),offsetof(Emu,trace_ring_pos),_Alignof(Emu));
    return 0;
}
