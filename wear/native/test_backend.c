#define _DARWIN_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#include "backend.h"
#include "sha256.h"
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <stddef.h>
#ifdef WEAR_FAULT_TEST
#include <dlfcn.h>
#endif
#define CHECK(cond, label) do { if(!(cond)) {fprintf(stderr,"FAIL: %s\n",label);exit(1);} checks++; } while(0)
static unsigned checks;
static char error[160];
static uint32_t frame[PANEL_W*PANEL_H], other[PANEL_W*PANEL_H];
static void advance(WearBackend *b,int n,uint32_t *out) {
    for(int i=0;i<n;i++) {
        if(!wear_advance(b,100,out,PANEL_W*PANEL_H,error)) {fprintf(stderr,"FAIL: foreground step: %s\n",error);exit(1);}
    }
}
static void path(char out[1024],const char *dir,const char *name) {snprintf(out,1024,"%s/%s",dir,name);}
static void copy_file(const char *from,const char *to) {
    FILE *in=fopen(from,"rb"),*out=fopen(to,"wb");CHECK(in&&out,"local fixture open");
    char buf[8192];size_t n;
    while((n=fread(buf,1,sizeof buf,in))) CHECK(fwrite(buf,1,n,out)==n,"local fixture copy");
    CHECK(!ferror(in),"local fixture read");fclose(in);CHECK(fclose(out)==0,"local fixture close");
}
static void file_digest(const char *name,uint8_t out[32]) {
    FILE *f=fopen(name,"rb");CHECK(f!=NULL,"save fixture read");CHECK(fseek(f,0,SEEK_END)==0,"save size");long n=ftell(f);
    CHECK(n>=0 && fseek(f,0,SEEK_SET)==0,"save rewind");uint8_t *p=malloc((size_t)n);CHECK(p!=NULL,"save fixture allocation");
    CHECK(fread(p,1,(size_t)n,f)==(size_t)n,"save fixture bytes");fclose(f);wear_sha256(p,(size_t)n,out);free(p);
}
static void equal_state(WearBackend *a,WearBackend *b,const char *label) {
    uint8_t x[32],y[32];wear_state_digest(a,x);wear_state_digest(b,y);CHECK(!memcmp(x,y,32),label);
}
static void rejection(const char *rom,const char *bad,const char *valid,WearBackend *live,const char *label) {
    uint8_t before[32],after[32],disk[32],disk_after[32];wear_state_digest(live,before);file_digest(valid,disk);
    WearBackend *b=wear_open(rom,bad,error);CHECK(b==NULL,label);wear_close(b);
    wear_state_digest(live,after);file_digest(valid,disk_after);
    CHECK(!memcmp(before,after,32),"failed open leaves live state intact");CHECK(!memcmp(disk,disk_after,32),"failed open preserves previous save");
}
static void persistence(const char *rom,const char *dir,WearBackend *b,const char *save) {
    CHECK(wear_save(b,error),"complete save succeeds");WearBackend *c=wear_open(rom,save,error);CHECK(c!=NULL,"complete save reload");
    equal_state(b,c,"complete canonical state and NOR identical after reload");
    for(int i=0;i<6;i++) {
        CHECK(wear_button(b,i%3,i<3),"button on live state");CHECK(wear_button(c,i%3,i<3),"button on reloaded state");
        advance(b,1,frame);advance(c,1,other);
        CHECK(!memcmp(frame,other,sizeof frame),"continued frames identical");equal_state(b,c,"continued CPU RAM clocks LCD timers NOR identical");
    }
    CHECK(wear_save(b,error),"save after continued execution");wear_close(c);
    char bad[1024];path(bad,dir,"invalid.sav");copy_file(save,bad);
    int fd=open(bad,O_RDWR);CHECK(fd>=0,"corruption fixture open");CHECK(lseek(fd,100,SEEK_SET)==100,"corruption offset");
    uint8_t byte;CHECK(read(fd,&byte,1)==1,"corruption read");byte^=0x80;CHECK(lseek(fd,100,SEEK_SET)==100 && write(fd,&byte,1)==1,"corruption write");close(fd);
    rejection(rom,bad,save,b,"corrupt payload rejected");
    copy_file(save,bad);CHECK(truncate(bad,80)==0,"truncate header fixture");rejection(rom,bad,save,b,"truncated header rejected");
    copy_file(save,bad);struct stat st;CHECK(stat(bad,&st)==0 && truncate(bad,st.st_size-1)==0,"truncate payload fixture");rejection(rom,bad,save,b,"truncated payload rejected");
    copy_file(save,bad);fd=open(bad,O_WRONLY);CHECK(fd>=0 && lseek(fd,8,SEEK_SET)==8 && write(fd,"\4",1)==1,"format version fixture");close(fd);rejection(rom,bad,save,b,"incompatible save rejected");
    copy_file(save,bad);fd=open(bad,O_WRONLY);CHECK(fd>=0 && lseek(fd,24,SEEK_SET)==24,"ROM identity fixture");
    CHECK(write(fd,"WRONGROM",8)==8,"ROM identity mutation");close(fd);rejection(rom,bad,save,b,"wrong ROM identity rejected");
    char alternate[1024];path(alternate,dir,"alternate.bin");copy_file(rom,alternate);
    fd=open(alternate,O_RDWR);CHECK(fd>=0 && lseek(fd,PS_ROM_SIZE-1,SEEK_SET)==PS_ROM_SIZE-1,"alternate ROM offset");
    CHECK(read(fd,&byte,1)==1,"alternate ROM read");byte^=1;CHECK(lseek(fd,PS_ROM_SIZE-1,SEEK_SET)==PS_ROM_SIZE-1 && write(fd,&byte,1)==1,"alternate ROM change");close(fd);
    rejection(alternate,save,save,b,"actual different ROM rejected");
    /* A valid checksum cannot bypass structural state validation. ext_count is
     * the 103rd byte of the v1 state schema, after CPU registers and flags. */
    copy_file(save,bad);FILE *malformed=fopen(bad,"r+b");CHECK(malformed!=NULL,"structural fixture open");
    CHECK(fseek(malformed,0,SEEK_END)==0,"structural size");long length=ftell(malformed);rewind(malformed);
    uint8_t *blob=malloc((size_t)length);CHECK(blob!=NULL && fread(blob,1,(size_t)length,malformed)==(size_t)length,"structural fixture read");
    blob[96+102]=3;wear_sha256(blob+96,(size_t)length-96,blob+56);rewind(malformed);
    CHECK(fwrite(blob,1,(size_t)length,malformed)==(size_t)length,"structural fixture write");fclose(malformed);free(blob);
    rejection(rom,bad,save,b,"checksummed invalid CPU prefix state rejected");
    /* Existing destination directory causes atomic rename failure. Session stays
     * usable and existing contents survive; no synthetic disk-full assumptions. */
    char marker[1024],failure[1024];path(failure,dir,"blocked.sav");CHECK(mkdir(failure,0700)==0,"failure destination directory");
    path(marker,failure,"preserve");FILE *f=fopen(marker,"wb");CHECK(f!=NULL && fwrite("keep",1,4,f)==4,"failure marker");fclose(f);
    char fresh[1024];path(fresh,dir,"fresh.sav");WearBackend *d=wear_open(rom,fresh,error);CHECK(d!=NULL,"failure fixture fresh open");
    CHECK(rename(failure,fresh)==0,"failure fixture replacement");
    uint8_t x[32],y[32];wear_state_digest(d,x);CHECK(!wear_save(d,error),"atomic replacement failure reported");wear_state_digest(d,y);
    CHECK(!memcmp(x,y,32),"failed save leaves session usable");path(marker,fresh,"preserve");CHECK(access(marker,F_OK)==0,"failed save preserves destination");wear_close(d);
    /* A non-root host can force temporary-file creation to fail while the
     * previous valid save remains readable. Restore permissions before checks. */
    if(geteuid()!=0) {
        uint8_t disk_before[32],disk_after[32],live_before[32],live_after[32];
        file_digest(save,disk_before);wear_state_digest(b,live_before);
        CHECK(chmod(dir,0500)==0,"read-only save parent");
        int saved=wear_save(b,error);int reset=chmod(dir,0700);
        CHECK(reset==0,"restore save parent permissions");CHECK(!saved,"write failure with previous valid save reported");
        file_digest(save,disk_after);wear_state_digest(b,live_after);
        CHECK(!memcmp(disk_before,disk_after,32),"write failure preserves previous valid save bytes");
        CHECK(!memcmp(live_before,live_after,32),"write failure preserves live state");
        WearBackend *preserved=wear_open(rom,save,error);CHECK(preserved!=NULL,"previous valid save reloads after write failure");wear_close(preserved);
    }
    uint8_t before[32],after[32];wear_state_digest(b,before);
    CHECK(!wear_advance(b,-1,frame,PANEL_W*PANEL_H,error),"negative elapsed rejected");
    CHECK(!wear_advance(b,1,frame,PANEL_W*PANEL_H-1,error),"wrong frame size rejected");CHECK(!wear_button(b,3,1),"invalid button rejected");
    wear_state_digest(b,after);CHECK(!memcmp(before,after,32),"invalid calls do not mutate state");
}
/* Independent normative old-format writer: serialized field schema and NOR,
 * with a literal v1 or v2 envelope. It never truncates a current writer payload. */
static void fixture_u64(uint8_t *p,uint64_t n) {for(int i=0;i<8;i++) p[i]=(uint8_t)(n>>(8*i));}
static void fixture_scalar(uint8_t **p,const void *src,size_t width) {
    uint64_t n=0;
    if(width==1) n=*(const uint8_t *)src;
    else if(width==2) {uint16_t x;memcpy(&x,src,2);n=x;}
    else if(width==4) {uint32_t x;memcpy(&x,src,4);n=x;}
    else memcpy(&n,src,8);
    for(size_t i=0;i<width;i++) *(*p)++=(uint8_t)(n>>(8*i));
}
static void old_fixture(const char *rom,const char *save,const Emu *e,int version,double deadline) {
    /* Normative guest length from the frozen original field schema. */
    const size_t guest_n=8536341,n=guest_n+(version==2?8:0);
    uint8_t *blob=calloc(1,96+n),*cursor=blob+96;CHECK(blob!=NULL,"old writer fixture allocation");
#define FIELD(member,kind,type) do {for(size_t j=0;j<sizeof(e->member)/sizeof(type);j++) fixture_scalar(&cursor,((const type *)&e->member)+j,sizeof(type));} while(0);
#define STRIDED(member,sub,kind,type) do {for(size_t j=0;j<sizeof(e->member)/sizeof(e->member[0]);j++) fixture_scalar(&cursor,&e->member[j].sub,sizeof(type));} while(0);
#include "state_fields.inc"
#undef FIELD
#undef STRIDED
    CHECK((size_t)(cursor-blob)==96+147733,"frozen v1 guest state length");
    memcpy(cursor,e->rom,8388608);cursor+=8388608;
    if(version==2) {uint64_t bits;memcpy(&bits,&deadline,8);fixture_u64(cursor,bits);}
    memcpy(blob,"TAMAWEAR",8);fixture_u64(blob+8,(uint64_t)version);fixture_u64(blob+16,n);
    file_digest(rom,blob+24);wear_sha256(blob+96,n,blob+56);memcpy(blob+88,"idl",3);
    FILE *f=fopen(save,"wb");CHECK(f && fwrite(blob,1,96+n,f)==96+n,"normative old-format fixture written");CHECK(fclose(f)==0,"old writer fixture close");free(blob);
}
static void v1_migration(const char *rom,const char *dir,WearBackend *b,const char *save) {
    (void)save;
    for(int version=1;version<=2;version++) {
        char legacy[1024];path(legacy,dir,version==1?"legacy-v1.sav":"legacy-v2.sav");
        double deadline=wear_state(b)->emu_secs-(version==2?0.0001:0);
        old_fixture(rom,legacy,wear_state(b),version,deadline);
        FILE *f=fopen(legacy,"rb");CHECK(f && fseek(f,0,SEEK_END)==0,"old fixture read");
        long length=ftell(f);rewind(f);uint8_t *before=malloc(8536509),*after=malloc(8536509);
        CHECK(before && after && fread(before,1,(size_t)length,f)==(size_t)length,"old fixture bytes");fclose(f);
        WearBackend *old=wear_open(rom,legacy,error);CHECK(old!=NULL,"normative v1/v2 opens without reset");
        uint64_t life[8]={9};CHECK(wear_read_life(old,life),"legacy life read");
        for(int i=0;i<8;i++) CHECK(life[i]==0,"legacy has no invented life metadata");
        uint8_t receipt[32];CHECK(wear_committed_payload_hash(old,receipt) && !memcmp(receipt,before+56,32),"legacy receipt is actual validated old-format checksum");
        CHECK(wear_state(old)->cycles==wear_state(b)->cycles && wear_state(old)->emu_secs==wear_state(b)->emu_secs,"old migration retains guest time and cycles");
        CHECK(wear_save(old,error),"old record migrates on explicit save");
        f=fopen(legacy,"rb");CHECK(f && fread(after,1,8536509,f)==8536509 && fgetc(f)==EOF,"v3 exact full file size 8536509");fclose(f);
        CHECK(after[8]==3 && !memcmp(before+96,after+96,8536341),"migration preserves every original guest and NOR byte and offset");
        uint64_t bits;memcpy(&bits,&deadline,8);uint8_t encoded_deadline[8];fixture_u64(encoded_deadline,bits);
        CHECK(!memcmp(after+96+8536341,encoded_deadline,8),"old pacing deadline retained or v1 anchored exactly at guest time");
        for(int i=0;i<64;i++) CHECK(after[8536509-64+i]==0,"migration writes uninitialized host metadata only");
        CHECK(wear_committed_payload_hash(old,receipt) && !memcmp(receipt,after+56,32),"migration refreshes exact v3 commit receipt");
        WearBackend *reopened=wear_open(rom,legacy,error);CHECK(reopened!=NULL,"migrated v3 reopens");equal_state(old,reopened,"migrated full state and deadline persist");
        CHECK(wear_advance(old,1,frame,PANEL_W*PANEL_H,error) && wear_advance(reopened,1,other,PANEL_W*PANEL_H,error),"migrated continuation advances");
        equal_state(old,reopened,"migrated deterministic continuation");CHECK(!memcmp(frame,other,sizeof frame),"migrated continued frames identical");
        wear_close(old);wear_close(reopened);free(before);free(after);
    }
}
/* Catches masking retained GRAM with firmware sleep flags in foreground UI.
 * Rendering must not wake the guest or turn this into a persisted preference. */
static void foreground_display(const char *rom,const char *dir) {
    char save[1024];path(save,dir,"foreground-display.sav");WearBackend *b=wear_open(rom,save,error);
    CHECK(b!=NULL,"foreground display fixture opens");Emu *e=(Emu *)wear_state(b);
    e->lcd.gram[7][9]=0xf800;e->lcd.sleep_out=false;e->lcd.disp_on=false;e->stay_awake=false;
    uint8_t before[32],after[32];wear_state_digest(b,before);
    CHECK(wear_advance(b,0,frame,PANEL_W*PANEL_H,error),"foreground retained frame renders");
    CHECK(frame[7*PANEL_W+9]==0xffff0000,"foreground retains GRAM while firmware LCD sleeps");
    CHECK(!e->lcd.sleep_out && !e->lcd.disp_on && !e->stay_awake,"foreground rendering preserves firmware power flags and preference");
    wear_state_digest(b,after);CHECK(!memcmp(before,after,32),"foreground render leaves every guest NOR and pacing byte unchanged");
    CHECK(wear_save(b,error),"sleeping display checkpoint");WearBackend *c=wear_open(rom,save,error);
    CHECK(c!=NULL,"sleeping display checkpoint reopens");equal_state(b,c,"sleeping LCD state retained across reopen");
    CHECK(wear_advance(c,0,other,PANEL_W*PANEL_H,error),"reopened retained frame renders");
    CHECK(!memcmp(frame,other,sizeof frame),"retained foreground display remains visible after reopen");
    equal_state(b,c,"reopened foreground rendering does not mutate sleeping guest");
    wear_close(b);wear_close(c);
}
/* Catches re-anchoring every request on the last rounded peripheral tick.
 * At 16384Hz one legitimate 256-cycle quantum is exactly 15.625ms. */
static void timing(const char *rom,const char *dir) {
    char save[1024];path(save,dir,"timing.sav");WearBackend *b=wear_open(rom,save,error);
    CHECK(b!=NULL,"timing fixture opens");Emu *e=(Emu *)wear_state(b);
    e->halted=true;e->halt_hard=true;e->cmu.mclk_hz=16384;
    for(int i=0;i<1000;i++) CHECK(wear_advance(b,33,frame,PANEL_W*PANEL_H,error),"low-clock timing advance");
    printf("Timing scalar: 1000 x 33ms requested=33.000000s actual=%.9fs\n",e->emu_secs);
    CHECK(e->emu_secs>=33.0-1e-9 && e->emu_secs<=33.015625+1e-9,
          "cumulative low-clock elapsed stays within one peripheral quantum");
    uint8_t before[32],after[32];wear_state_digest(b,before);
    for(int i=0;i<20;i++) CHECK(wear_advance(b,0,frame,PANEL_W*PANEL_H,error),"zero elapsed renders without advancing");
    CHECK(!wear_advance(b,-1,frame,PANEL_W*PANEL_H,error),"timing negative input rejected");
    CHECK(!wear_advance(b,1,NULL,PANEL_W*PANEL_H,error),"timing null buffer rejected");
    CHECK(!wear_advance(b,1,frame,PANEL_W*PANEL_H-1,error),"timing wrong buffer rejected");
    wear_state_digest(b,after);CHECK(!memcmp(before,after,32),"zero and invalid calls preserve guest and pacing state");
    CHECK(wear_save(b,error),"low-clock pacing checkpoint");WearBackend *c=wear_open(rom,save,error);CHECK(c!=NULL,"low-clock pacing reopen");
    equal_state(b,c,"nonzero pacing overshoot survives save and reopen");
    FILE *f=fopen(save,"rb");CHECK(f!=NULL && fseek(f,0,SEEK_END)==0,"pacing rejection fixture open");
    long length=ftell(f);rewind(f);uint8_t *blob=malloc((size_t)length);
    CHECK(blob!=NULL && fread(blob,1,(size_t)length,f)==(size_t)length,"pacing rejection fixture bytes");fclose(f);
    /* Current saved guest time is 33.015625s: test NaN, infinity, negative,
     * future deadline and positive two-second debt with valid checksums. */
    const double invalid[]={NAN,INFINITY,-1.0,34.0,31.0};char bad[1024];path(bad,dir,"bad-pacing.sav");
    for(size_t i=0;i<sizeof invalid/sizeof *invalid;i++) {
        uint64_t bits;memcpy(&bits,&invalid[i],8);
        for(int byte=0;byte<8;byte++) blob[length-72+byte]=(uint8_t)(bits>>(8*byte));
        wear_sha256(blob+96,(size_t)length-96,blob+56);
        f=fopen(bad,"wb");CHECK(f!=NULL && fwrite(blob,1,(size_t)length,f)==(size_t)length,"invalid pacing fixture write");fclose(f);
        rejection(rom,bad,save,b,"checksummed invalid pacing rejected without changing guest or save");
    }
    free(blob);

    const double hz[]={16384,18432000,32768,16384};const int ms[]={1,17,100,33};
    double wanted=33;
    for(int round=0;round<40;round++) for(int i=0;i<4;i++) {
        ((Emu *)wear_state(b))->cmu.mclk_hz=hz[i];((Emu *)wear_state(c))->cmu.mclk_hz=hz[i];wanted+=(double)ms[i]/1000;
        CHECK(wear_advance(b,ms[i],frame,PANEL_W*PANEL_H,error),"varying-clock original advances");
        CHECK(wear_advance(c,ms[i],other,PANEL_W*PANEL_H,error),"varying-clock reloaded advances");
        CHECK(wear_state(b)->emu_secs>=wanted-1e-8 && wear_state(b)->emu_secs<=wanted+0.015625+1e-8,
              "varying clocks and slices retain cumulative elapsed within one quantum");
        CHECK(!memcmp(frame,other,sizeof frame),"varying-clock continued frames match");
    }
    equal_state(b,c,"varying-clock full state NOR and pacing continuation match");
    CHECK(wear_advance(b,1000,frame,PANEL_W*PANEL_H,error) && wear_advance(c,100,other,PANEL_W*PANEL_H,error),"elapsed work cap retained");
    equal_state(b,c,"oversized input has same result as capped 100ms");
    wear_close(c);v1_migration(rom,dir,b,save);wear_close(b);
    puts("Cumulative timing, clock changes, invalid/zero inputs, pacing persistence and v1 migration passed");
}
/* Detect headless time drift, lost pacing, unintended rendering state changes,
 * and lost continuation across checkpoints against the existing rendered path. */
static void headless(const char *rom,const char *dir,int synthetic_rom) {
    char save[1024];path(save,dir,"headless.sav");
    WearBackend *rendered=wear_open(rom,save,error);CHECK(rendered!=NULL,"headless reference opens");
    if(synthetic_rom) {
        Emu *e=(Emu *)wear_state(rendered);e->halted=true;e->halt_hard=true;e->cmu.mclk_hz=16384;
        e->lcd.gram[7][9]=0xf800;e->lcd.sleep_out=false;e->lcd.disp_on=false;
    }
    CHECK(wear_save(rendered,error),"headless initial checkpoint");
    WearBackend *plain=wear_open(rom,save,error);CHECK(plain!=NULL,"headless branch opens");
    const int chunks[]={1,17,33,100,1000,0,7};
    for(int round=0;round<3;round++) for(size_t i=0;i<sizeof chunks/sizeof *chunks;i++) {
        CHECK(wear_advance_headless(plain,chunks[i],error),"headless advance succeeds without pixel buffer");
        CHECK(wear_advance(rendered,chunks[i],frame,PANEL_W*PANEL_H,error),"headless reference advances");
        equal_state(plain,rendered,"headless matches every canonical guest NOR and pacing byte");
    }
    /* Literal 100ms reference prevents a shared-loop cap regression from
     * making both oversized paths agree at the wrong elapsed duration. */
    CHECK(wear_advance_headless(plain,1000,error),"headless oversized elapsed accepted");
    CHECK(wear_advance(rendered,100,frame,PANEL_W*PANEL_H,error),"headless literal cap reference");
    equal_state(plain,rendered,"headless oversized request caps at literal 100ms");
    uint8_t before[32],after[32];wear_state_digest(plain,before);
    CHECK(wear_advance_headless(plain,0,error),"headless zero elapsed accepted");
    wear_state_digest(plain,after);CHECK(!memcmp(before,after,32),"headless zero elapsed immutable");
    CHECK(!wear_advance_headless(NULL,1,error),"headless null backend rejected");
    CHECK(!wear_advance_headless(plain,-1,error),"headless negative elapsed rejected");
    wear_state_digest(plain,after);CHECK(!memcmp(before,after,32),"headless invalid request immutable");
    CHECK(wear_save(plain,error),"headless checkpoint saves");wear_close(plain);
    plain=wear_open(rom,save,error);CHECK(plain!=NULL,"headless checkpoint reopens");
    equal_state(plain,rendered,"headless checkpoint retains canonical bytes and pacing");
    for(int i=0;i<13;i++) {
        CHECK(wear_advance_headless(plain,33,error),"headless reopened continuation");
        CHECK(wear_advance(rendered,33,frame,PANEL_W*PANEL_H,error),"headless uninterrupted reference continuation");
    }
    equal_state(plain,rendered,"headless reopened continuation matches uninterrupted reference");
    CHECK(wear_save(plain,error),"varied partition initial checkpoint");
    char partition[1024];path(partition,dir,"headless-partition.sav");copy_file(save,partition);
    wear_close(plain);wear_close(rendered);
    plain=wear_open(rom,partition,error);rendered=wear_open(rom,save,error);
    CHECK(plain && rendered,"varied partition branches reopen");
    for(int i=0;i<10;i++) {
        CHECK(wear_advance_headless(plain,17,error) && wear_advance_headless(plain,33,error) &&
              wear_advance_headless(plain,50,error),"headless partitioned 100ms");
        CHECK(wear_advance(rendered,100,frame,PANEL_W*PANEL_H,error),"rendered unpartitioned 100ms");
    }
    CHECK(wear_save(plain,error) && wear_save(rendered,error),"varied partition final checkpoints");
    FILE *a=fopen(partition,"rb"),*b=fopen(save,"rb");
    CHECK(a && b && fseek(a,0,SEEK_END)==0,"varied partition files open");
    long length=ftell(a);CHECK(length>104 && fseek(a,96,SEEK_SET)==0 && fseek(b,96,SEEK_SET)==0,
                              "varied partition canonical payload offsets");
    uint8_t *x=malloc((size_t)length-96),*y=malloc((size_t)length-96);
    CHECK(x && y && fread(x,1,(size_t)length-96,a)==(size_t)length-96 &&
          fread(y,1,(size_t)length-96,b)==(size_t)length-96 && fgetc(a)==EOF && fgetc(b)==EOF,
          "varied partition canonical payloads read");fclose(a);fclose(b);
    CHECK(!memcmp(x,y,(size_t)length-168),"varied chunk partitions preserve every canonical guest and NOR byte");
    uint64_t ux=0,uy=0;for(int i=0;i<8;i++) {ux|=(uint64_t)x[length-168+i]<<(8*i);uy|=(uint64_t)y[length-168+i]<<(8*i);}
    double dx,dy;memcpy(&dx,&ux,8);memcpy(&dy,&uy,8);
    CHECK(fabs(dx-dy)<1e-12,"partition pacing differs by at most floating addition rounding");
    free(x);free(y);wear_close(plain);wear_close(rendered);
    puts("Headless digest equivalence, cap, zero/invalid inputs, partitions and save/reopen continuation passed");
}
/* Missing validation, shallow copies, mutable receipt recomputation and excluded
 * trailer checksums each break an externally visible checkpoint contract. */
static void life_metadata(const char *rom,const char *dir) {
    char save[1024],bad[1024];path(save,dir,"life.sav");path(bad,dir,"life-invalid.sav");
    WearBackend *b=wear_open(rom,save,error);CHECK(b!=NULL,"life fixture opens");
    uint8_t receipt[32],prior[32],digest[32],after[32],guest_hash[32];memset(receipt,0xa5,32);
    CHECK(!wear_committed_payload_hash(b,receipt) && receipt[0]==0xa5,"no fabricated receipt before first commit");
    uint64_t zero[8]={0},out[8],valid[8]={1,1,0,INT64_MAX,INT64_MAX,1,1,1};
    CHECK(wear_read_life(b,out) && !memcmp(out,zero,sizeof out),"fresh ledger all zero");
    CHECK(!wear_read_life(NULL,out) && !wear_read_life(b,NULL) && !wear_committed_payload_hash(NULL,receipt) && !wear_committed_payload_hash(b,NULL),"null readers fail safely");
    Emu *timed=(Emu *)wear_state(b);timed->halted=true;timed->halt_hard=true;timed->cmu.mclk_hz=16384;
    CHECK(wear_advance_headless(b,17,error),"nonzero fractional deadline before life mutation");
    CHECK(wear_save(b,error),"initial life checkpoint");
    FILE *f=fopen(save,"rb");CHECK(f && fseek(f,56,SEEK_SET)==0 && fread(prior,1,32,f)==32,"read committed header hash");
    uint8_t *prefix=malloc(8536349);CHECK(prefix && fseek(f,96,SEEK_SET)==0 && fread(prefix,1,8536349,f)==8536349,"original guest NOR and pacing prefix");fclose(f);wear_sha256(prefix,8536349,guest_hash);free(prefix);
    CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,prior,32),"receipt matches exact committed payload header");
    receipt[0]^=1;CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,prior,32),"receipt copy cannot mutate cached commit");
    const Emu *e=wear_state(b);Emu *guest=malloc(sizeof *guest);CHECK(guest!=NULL,"guest comparison allocation");memcpy(guest,e,sizeof *guest);
    CHECK(wear_write_life(b,valid,error),"valid initialized metadata including UNKNOWN boot and max debt");
    CHECK(!memcmp(guest,e,sizeof *guest),"metadata write leaves complete guest and NOR pointer unchanged");
    CHECK(wear_read_life(b,out) && !memcmp(out,valid,sizeof out),"valid ledger copies all exact slots");
    out[1]=100;CHECK(wear_read_life(b,out) && out[1]==1,"read returns detached copy");
    CHECK(valid[1]==1 && valid[4]==INT64_MAX,"input source array unchanged");
    CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,prior,32),"working candidate never replaces committed receipt");
    wear_state_digest(b,digest);CHECK(memcmp(digest,prior,32)!=0,"full v3 digest includes working life trailer");
    for(int slot=0;slot<8;slot++) {
        uint64_t invalid[8];memcpy(invalid,valid,sizeof invalid);invalid[slot]=UINT64_MAX;
        CHECK(!wear_write_life(b,invalid,error),"every unsigned slot rejects values above INT64_MAX");
        wear_state_digest(b,after);CHECK(!memcmp(after,digest,32),"invalid late field cannot partially install candidate");
    }
    const uint64_t invalids[][8]={{0,1,0,0,0,0,0,0},{2,1,0,0,0,0,1,0},{1,0,0,0,0,0,1,0},{1,1,0,0,0,2,1,0},{1,1,0,0,0,0,0,0},{1,1,0,0,0,0,1,2}};
    for(size_t i=0;i<sizeof invalids/sizeof *invalids;i++) CHECK(!wear_write_life(b,invalids[i],error),"invalid schema initialization wall generation or enum rejected");
    CHECK(!wear_write_life(b,NULL,error) && !wear_write_life(NULL,valid,error),"null metadata writes rejected");
    uint64_t boundary[8]={1,INT64_MAX,INT64_MAX,0,0,0,INT64_MAX,0};
    CHECK(wear_write_life(b,boundary,error) && wear_read_life(b,out) && !memcmp(out,boundary,sizeof out),"known boot zero and every inclusive signed boundary valid");
    for(int slot=1;slot<8;slot++) {uint64_t nonzero[8]={0};nonzero[slot]=1;CHECK(!wear_write_life(b,nonzero,error),"schema zero rejects each nonzero slot");}
    CHECK(wear_write_life(b,valid,error),"restore initialized candidate");
    CHECK(wear_save(b,error),"candidate life checkpoint saves");
    f=fopen(save,"rb");CHECK(f && fseek(f,0,SEEK_END)==0 && ftell(f)==8536509,"v3 file size");rewind(f);
    uint8_t *blob=malloc(8536509);CHECK(blob && fread(blob,1,8536509,f)==8536509,"v3 checkpoint bytes");fclose(f);
    for(int slot=0;slot<8;slot++) {uint8_t expected[8];fixture_u64(expected,valid[slot]);CHECK(!memcmp(blob+8536445+8*slot,expected,8),"stable eight-slot little-endian ABI and order");}
    wear_sha256(blob+96,8536349,after);CHECK(!memcmp(after,guest_hash,32),"life edits preserve every guest NOR and nonzero deadline byte");
    wear_sha256(blob+96,8536413,after);CHECK(!memcmp(after,blob+56,32),"entire v3 payload covered by header checksum");
    CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,after,32),"successful receipt updated exactly");
    for(int slot=0;slot<8;slot++) {
        uint8_t *field=blob+8536509-64+slot*8;uint64_t saved=valid[slot];
        fixture_u64(field,UINT64_MAX);wear_sha256(blob+96,8536413,blob+56);
        f=fopen(bad,"wb");CHECK(f && fwrite(blob,1,8536509,f)==8536509,"checksummed invalid life write");fclose(f);
        rejection(rom,bad,save,b,"checksummed invalid life slot rejected");fixture_u64(field,saved);
    }
    for(size_t i=0;i<sizeof invalids/sizeof *invalids;i++) {
        for(int slot=0;slot<8;slot++) fixture_u64(blob+8536445+8*slot,invalids[i][slot]);
        wear_sha256(blob+96,8536413,blob+56);f=fopen(bad,"wb");CHECK(f && fwrite(blob,1,8536509,f)==8536509,"invalid initialized on-disk ledger fixture");fclose(f);
        CHECK(wear_open(rom,bad,error)==NULL,"checksummed invalid initialization or enum rejected on restore");
    }
    for(int slot=0;slot<8;slot++) {
        copy_file(save,bad);int fd=open(bad,O_RDWR);CHECK(fd>=0 && lseek(fd,8536445+8*slot,SEEK_SET)==8536445+8*slot,"trailer checksum fixture offset");
        uint8_t byte;CHECK(read(fd,&byte,1)==1,"trailer checksum fixture read");byte^=1;CHECK(lseek(fd,-1,SEEK_CUR)>=0 && write(fd,&byte,1)==1,"trailer checksum fixture mutation");close(fd);
        CHECK(wear_open(rom,bad,error)==NULL,"each trailer slot covered by checksum");
    }
    for(int bytes=1;bytes<=64;bytes++) {copy_file(save,bad);CHECK(truncate(bad,8536509-bytes)==0,"truncated life trailer fixture");CHECK(wear_open(rom,bad,error)==NULL,"every trailer truncation rejected");}
    WearBackend *c=wear_open(rom,save,error);CHECK(c && wear_read_life(c,out) && !memcmp(out,valid,sizeof out),"life metadata persists across reopen");
    equal_state(b,c,"life guest pacing and metadata reopen identical");
    CHECK(wear_write_life(b,zero,error) && wear_read_life(b,out) && !memcmp(out,zero,sizeof zero),"schema0 accepts exactly all zero");
    free(blob);free(guest);wear_close(c);wear_close(b);
}
#ifdef WEAR_FAULT_TEST
/* Executable-local interposition only. No test hooks enter the production API. */
static int fail_sync_kind,fail_rename;
int fsync(int fd) {
    struct stat st;CHECK(fstat(fd,&st)==0,"fault fd classification");
    if(fail_sync_kind && (S_ISDIR(st.st_mode)?2:1)==fail_sync_kind) {fail_sync_kind=0;errno=EIO;return -1;}
    int (*real_sync)(int)=(int (*)(int))dlsym(RTLD_NEXT,"fsync");return real_sync(fd);
}
int rename(const char *from,const char *to) {
    if(fail_rename) {fail_rename=0;errno=EIO;return -1;}
    int (*real_rename)(const char *,const char *)=(int (*)(const char *,const char *))dlsym(RTLD_NEXT,"rename");return real_rename(from,to);
}
static void persistence_faults(const char *rom,const char *dir) {
    char save[1024];path(save,dir,"life-fault.sav");WearBackend *b=wear_open(rom,save,error);CHECK(b!=NULL,"fault backend opens");
    Emu *e=(Emu *)wear_state(b);e->halted=true;e->halt_hard=true;e->cmu.mclk_hz=16384;
    uint64_t life[8]={1,100,50,0,1000,0,1,0};CHECK(wear_write_life(b,life,error) && wear_save(b,error),"fault base durable checkpoint");
    for(int kind=1;kind<=3;kind++) {
        uint8_t old[32],disk_before[32],disk_after[32],candidate[32],after[32],receipt[32];
        CHECK(wear_committed_payload_hash(b,old),"fault original receipt");file_digest(save,disk_before);
        CHECK(wear_advance_headless(b,17,error),"guest and pacing advance before interrupted candidate commit");
        life[6]++;life[4]--;CHECK(wear_write_life(b,life,error),"next fault candidate");wear_state_digest(b,candidate);
        if(kind==3) fail_rename=1;else fail_sync_kind=kind;
        CHECK(!wear_save(b,error),"file sync rename or parent sync failure reports false");
        if(kind==2) CHECK(strstr(error,"uncertain") && !strstr(error,"preserved"),"post-rename error describes uncertain durability");
        CHECK(!fail_sync_kind && !fail_rename,"requested persistence fault reached");
        wear_state_digest(b,after);CHECK(!memcmp(candidate,after,32),"failed commit preserves exact working candidate for retry");
        CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,old,32),"failed or uncertain commit retains previous receipt");
        file_digest(save,disk_after);
        if(kind!=2) CHECK(!memcmp(disk_before,disk_after,32),"pre-rename fault preserves previous complete file");
        else {
            CHECK(memcmp(disk_before,disk_after,32)!=0,"post-rename uncertainty retains actual new complete destination");
            WearBackend *restored=wear_open(rom,save,error);CHECK(restored!=NULL,"uncertain replacement still validates");equal_state(b,restored,"uncertain destination retains paired guest and candidate");wear_close(restored);
        }
        CHECK(wear_save(b,error),"same candidate retry reaches durable success");wear_state_digest(b,after);CHECK(!memcmp(after,candidate,32),"retry does not advance guest or generation");
        CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,candidate,32),"retry commits exact cached candidate payload hash");
    }
    wear_close(b);
}
#endif
static void synthetic(const char *dir) {
    char rom[1024],save[1024];path(rom,dir,"synthetic.bin");path(save,dir,"synthetic.sav");
    uint8_t *image=malloc(PS_ROM_SIZE);CHECK(image!=NULL,"synthetic allocation");memset(image,0xff,PS_ROM_SIZE);
    uint32_t reset=0x02400100;for(int i=0;i<4;i++) image[0x400000+i]=(uint8_t)(reset>>(i*8));
    FILE *f=fopen(rom,"wb");CHECK(f!=NULL && fwrite(image,1,PS_ROM_SIZE,f)==PS_ROM_SIZE,"synthetic firmware fixture");fclose(f);free(image);
    life_metadata(rom,dir);
#ifdef WEAR_FAULT_TEST
    persistence_faults(rom,dir);
#endif
    headless(rom,dir,1);
    foreground_display(rom,dir);
    timing(rom,dir);
    WearBackend *b=wear_open(rom,save,error);CHECK(b!=NULL,"fresh no-save initialization");Emu *e=(Emu *)wear_state(b);
    e->halted=true;e->halt_hard=true;e->r[15]=0x87654321;e->a0ram[300]=0x31;e->ivram[17]=0x42;e->dstram[5]=0x53;
    e->cmu.mclk_hz=e->dev.osc3_hz;e->lcd.sleep_out=true;e->lcd.disp_on=true;e->lcd.gram[7][9]=0xf800;
    e->t16[4].counter=123;e->tone_ev[1023].cyc=77;e->tone_ev[1023].freq=440;e->tone_ev[1023].on=1;e->tone_ev_w=1024;
    mem_write16(e,e->dev.rom_base+0xaaa,0xaa);mem_write16(e,e->dev.rom_base+0x554,0x55);
    mem_write16(e,e->dev.rom_base+0xaaa,0xa0);mem_write16(e,e->dev.rom_base+0x1000,0x1234);
    CHECK(e->flash_programs==1 && e->flash_dirty,"NOR programmed through upstream flash behavior");
    advance(b,1,frame);CHECK(frame[7*PANEL_W+9]==0xffff0000,"opaque RGB565 rendering");persistence(rom,dir,b,save);wear_close(b);
    puts("Synthetic persistence and rejection checks passed");
}
static void real_rom(const char *rom,const char *dir) {
    headless(rom,dir,0);
    char save[1024],bmp[1024];path(save,dir,"firmware.sav");path(bmp,dir,"boot.bmp");WearBackend *b=wear_open(rom,save,error);
    CHECK(b!=NULL,"local supplied firmware opens");
    advance(b,100,frame);const Emu *e=wear_state(b);
    CHECK(!e->stopped && e->cycles>1000000 && e->lcd.ramwr_bytes>1000,"local firmware boot executes and writes display");
    unsigned different=0;for(int i=1;i<PANEL_W*PANEL_H;i++) different+=frame[i]!=frame[0];
    CHECK(different>100,"boot display has nonblank varied pixels");CHECK(lcd_dump_bmp((Emu *)e,bmp)==0,"ignored local boot screenshot");
    CHECK(wear_save(b,error),"button branch checkpoint");WearBackend *control=wear_open(rom,save,error);CHECK(control!=NULL,"button control branch");
    for(int i=0;i<3;i++) {
        CHECK(wear_button(b,i,1),"A B C press accepted");CHECK((wear_state(b)->btn_mask&(1u<<i))!=0,"A B C reaches peripheral latch");
        CHECK(!(periph_read8((Emu *)wear_state(b),0x300380)&(1u<<i)),"A B C active-low GPIO asserted");
        advance(b,2,frame);CHECK(wear_button(b,i,0),"A B C release accepted");advance(b,3,frame);
        advance(control,5,other);
    }
    CHECK(memcmp(frame,other,sizeof frame)!=0,"button-driven firmware display differs from untouched control");wear_close(control);
    /* NOR belongs to this disposable test session, never the original ROM. */
    Emu *mutable=(Emu *)wear_state(b);mutable->rom[mutable->dev.rom_size-1]^=1;
    persistence(rom,dir,b,save);v1_migration(rom,dir,b,save);wear_close(b);
    puts("Local supplied-ROM boot, A/B/C GPIO and display response, deterministic save/reload passed");
}
/* Actual previous watch writers are local-only inputs. Operate on disposable
 * copies and verify the original backup remains byte-identical. */
static void real_old_fixture(const char *rom,const char *dir,const char *original,int version) {
    char copy[1024];path(copy,dir,version==1?"actual-v1.sav":"actual-v2.sav");
    uint8_t original_hash[32],after_hash[32];file_digest(original,original_hash);copy_file(original,copy);
    size_t old_n=version==1?8536437:8536445;uint8_t *before=malloc(8536509),*after=malloc(8536509);
    FILE *f=fopen(copy,"rb");CHECK(f && before && after && fread(before,1,old_n,f)==old_n && fgetc(f)==EOF,"genuine old writer size");fclose(f);
    CHECK(before[8]==version,"genuine old writer version");WearBackend *b=wear_open(rom,copy,error);CHECK(b!=NULL,"genuine old writer restore");
    uint64_t life[8],zero[8]={0};uint8_t receipt[32];CHECK(wear_read_life(b,life) && !memcmp(life,zero,sizeof life),"genuine old writer has zero metadata");
    CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,before+56,32),"genuine old writer exact validated receipt");
    double v1_deadline=wear_state(b)->emu_secs;CHECK(wear_save(b,error),"genuine old writer migration checkpoint");
    f=fopen(copy,"rb");CHECK(f && fread(after,1,8536509,f)==8536509 && fgetc(f)==EOF,"genuine migration exact v3 size");fclose(f);
    CHECK(after[8]==3 && !memcmp(before+96,after+96,8536341),"genuine migration every guest NOR byte and offset retained");
    if(version==2) CHECK(!memcmp(before+8536437,after+8536437,8),"genuine v2 exact deadline retained");
    else {uint64_t bits;memcpy(&bits,&v1_deadline,8);uint8_t expected[8];fixture_u64(expected,bits);CHECK(!memcmp(expected,after+8536437,8),"genuine v1 deadline anchored at original guest time");}
    for(int i=0;i<64;i++) CHECK(after[8536445+i]==0,"genuine old migration adds only zero life trailer");
    CHECK(wear_committed_payload_hash(b,receipt) && !memcmp(receipt,after+56,32),"genuine migrated commit exact checksum");
    WearBackend *c=wear_open(rom,copy,error);CHECK(c!=NULL,"genuine migrated v3 reopens");equal_state(b,c,"genuine migrated guest pacing metadata preserved");wear_close(b);wear_close(c);
    file_digest(original,after_hash);CHECK(!memcmp(original_hash,after_hash,32),"original backup remains untouched");free(before);free(after);
    printf("Genuine prior-writer v%d copy migrated to v3, 8536509 bytes; original unchanged\n",version);
}
int main(int argc,char **argv) {
    const uint8_t expected[32]={0xba,0x78,0x16,0xbf,0x8f,0x01,0xcf,0xea,0x41,0x41,0x40,0xde,0x5d,0xae,0x22,0x23,0xb0,0x03,0x61,0xa3,0x96,0x17,0x7a,0x9c,0xb4,0x10,0xff,0x61,0xf2,0x00,0x15,0xad};
    uint8_t digest[32];wear_sha256("abc",3,digest);CHECK(!memcmp(digest,expected,32),"SHA-256 known-answer");
    char synthetic_dir[]="native-synthetic-XXXXXX";CHECK(mkdtemp(synthetic_dir)!=NULL,"local synthetic output directory");synthetic(synthetic_dir);
    if(argc>1) {char real_dir[]="native-firmware-XXXXXX";CHECK(mkdtemp(real_dir)!=NULL,"local firmware output directory");real_rom(argv[1],real_dir);}
    if(argc==4) {char old_dir[]="native-old-writers-XXXXXX";CHECK(mkdtemp(old_dir)!=NULL,"local old writer output directory");real_old_fixture(argv[1],old_dir,argv[2],1);real_old_fixture(argv[1],old_dir,argv[3],2);}
    printf("PASS: %u checks%s\n",checks,argc>1?" including supplied local ROM":" (synthetic; no firmware provided)");return 0;
}
