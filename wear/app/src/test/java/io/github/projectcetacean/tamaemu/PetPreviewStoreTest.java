package io.github.projectcetacean.tamaemu;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Synthetic envelopes contain no firmware. Byte offsets come from the native save contract. */
public final class PetPreviewStoreTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static void rejects(byte[] bytes, String message) throws Exception {
        try { PetPreviewStore.decode(bytes); throw new AssertionError(message); }
        catch (IOException expected) { checks++; }
    }
    static byte[] hash(byte[] bytes) throws Exception { return MessageDigest.getInstance("SHA-256").digest(bytes); }
    static byte[] envelope(long generation, long mode, long debt, long schema) throws Exception {
        byte[] file = new byte[8536509];
        ByteBuffer b=ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        b.put("TAMAWEAR".getBytes("US-ASCII")).putLong(3).putLong(8536413);
        file[88]='i';file[89]='d';file[90]='l';
        b.position(file.length-64); b.putLong(schema).putLong(1800000000000L).putLong(1000).putLong(4)
            .putLong(debt).putLong(mode).putLong(generation).putLong(0);
        byte[] digest=hash(Arrays.copyOfRange(file,96,file.length)); System.arraycopy(digest,0,file,56,32);
        return file;
    }
    static PetPreviewSnapshot snapshot(byte[] file, long generation, PetPreviewSnapshot.Mode mode, boolean paused, long debt, int quality, int color) {
        int[] pixels=new int[128*128]; Arrays.fill(pixels,color);
        return new PetPreviewSnapshot(Arrays.copyOfRange(file,56,88),generation,1800000000000L,mode,paused,debt,quality,pixels);
    }
    public static void main(String[] args) throws Exception {
        File dir=Files.createTempDirectory("tile-synthetic-").toFile();
        try {
            byte[] save=envelope(7,0,90,1); PetPreviewSnapshot a=snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,90,0,0xff123456);
            byte[] encoded=PetPreviewStore.encode(a);
            check(PetPreviewStore.decode(encoded).pixelsArgb()[0]==0xff123456,"codec preserves opaque original pixels");
            int[] copy=a.pixelsArgb();copy[0]=0;check(a.pixelsArgb()[0]==0xff123456,"returned pixels cannot mutate snapshot");
            byte[] digest=a.savePayloadDigest();digest[0]^=1;check(Arrays.equals(a.savePayloadDigest(),Arrays.copyOfRange(save,56,88)),"digest immutable");
            int[] input=new int[16384];Arrays.fill(input,0xffabcdef);PetPreviewSnapshot immutable=new PetPreviewSnapshot(a.savePayloadDigest(),7,a.committedUtcMillis,a.mode,false,90,0,input);input[0]=0;
            check(immutable.pixelsArgb()[0]==0xffabcdef,"caller mutation does not alter snapshot");
            rejects(Arrays.copyOf(encoded,encoded.length-1),"truncated preview rejected");
            rejects(Arrays.copyOf(encoded,encoded.length+1),"trailing preview rejected");
            byte[] corrupt=encoded.clone();corrupt[corrupt.length-40]^=1;rejects(corrupt,"corrupt pixel rejected");
            byte[] huge=new byte[70000];rejects(huge,"oversize preview rejected");
            byte[] unknown=encoded.clone();unknown[8]=9;rejects(unknown,"unknown schema rejected");
            check(a.statusLabel().equals("Normal · Catch-up needed"),"normal debt visible");
            check(snapshot(save,7,PetPreviewSnapshot.Mode.PROTECTED,true,90,0,0xff123456).statusLabel().equals("Protected: paused away · Catch-up needed"),"protected preserves prior normal debt");
            check(snapshot(save,7,PetPreviewSnapshot.Mode.PROTECTED,false,0,1,0xff123456).statusLabel().equals("Protected · Time needs checking"),"active protected not called paused");
            PetPreviewSnapshot oddDate=new PetPreviewSnapshot(a.savePayloadDigest(),7,Long.MAX_VALUE,a.mode,false,0,0,a.pixelsArgb());
            check(oddDate.statusLabel().equals("Normal · Time needs checking"),"implausible date preserves preview with honest clock warning");
            check(snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,0,0,0xff123456).exceptionalStatus()==null,"normal with no debt and good clock has no exceptional status");
            for(long debt:new long[]{0,1,90,86400000,Long.MAX_VALUE}) {
                PetPreviewSnapshot routine=snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,debt,0,0xff123456);
                check(routine.exceptionalStatus()==null,"saved debt alone never demands Tile action, debt="+debt);
                check(routine.debtMillis==debt,"Tile presentation never changes exact owed time");
            }
            check("Protected".equals(snapshot(save,7,PetPreviewSnapshot.Mode.PROTECTED,false,0,0,0xff123456).exceptionalStatus()),"protected is exceptional");
            check("Protected: paused away".equals(snapshot(save,7,PetPreviewSnapshot.Mode.PROTECTED,true,0,0,0xff123456).exceptionalStatus()),"paused away is exceptional");
            check("Time needs checking".equals(snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,0,1,0xff123456).exceptionalStatus()),"clock quality is exceptional");
            check("Time needs checking".equals(oddDate.exceptionalStatus()),"implausible date is exceptional");
            check("Protected: paused away · Time needs checking".equals(snapshot(save,7,PetPreviewSnapshot.Mode.PROTECTED,true,90,1,0xff123456).exceptionalStatus()),"combined policy and clock notices omit checkpoint debt demand");
            PetPreviewStore store=new PetPreviewStore(dir);
            check(store.readVerified().state==PetPreviewStore.State.MISSING,"missing preview neutral");
            store.publish(a);
            check(store.readVerified().state==PetPreviewStore.State.UNAVAILABLE,"missing save/firmware neutral");
            Files.write(new File(dir,"firmware.bin").toPath(),new byte[1]);
            Files.write(new File(dir,"pet.state").toPath(),save);
            check(store.readVerified().state==PetPreviewStore.State.VALID,"synthetic v3 committed identity accepted");
            save[100]^=1;Files.write(new File(dir,"pet.state").toPath(),save);
            check(store.readVerified().state==PetPreviewStore.State.UNAVAILABLE,"payload corruption rejected despite unchanged header digest");
            save=envelope(8,0,90,1);Files.write(new File(dir,"pet.state").toPath(),save);
            check(store.readVerified().state==PetPreviewStore.State.MISMATCH,"newer committed generation neutral");
            save=envelope(7,1,90,1);Files.write(new File(dir,"pet.state").toPath(),save);
            store.publish(snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,90,0,0xff123456));
            check(store.readVerified().state==PetPreviewStore.State.MISMATCH,"cached mode must match committed receipt");
            save=envelope(7,0,90,0);Files.write(new File(dir,"pet.state").toPath(),save);
            check(store.readVerified().state==PetPreviewStore.State.UNAVAILABLE,"uninitialized clock receipt neutral");
            save=envelope(7,0,90,1);Files.write(new File(dir,"pet.state").toPath(),save);
            PetPreviewStore.CommittedReceipt receipt=PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"));
            check(receipt.wallUtcMillis==1800000000000L && receipt.elapsedRealtimeMillis==1000 && receipt.bootCount==4 && receipt.generation==7 && receipt.debtMillis==90 && receipt.mode==PetPreviewSnapshot.Mode.NORMAL && receipt.clockQuality==0,"all eight native receipt slots retain exact meaning");
            for(int slot:new int[]{0,1,2,3,4,5,6,7}) {
                byte[] invalid=save.clone();ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putLong(invalid.length-64+slot*8,-1);
                System.arraycopy(hash(Arrays.copyOfRange(invalid,96,invalid.length)),0,invalid,56,32);
                Files.write(new File(dir,"pet.state").toPath(),invalid);
                try{PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"));throw new AssertionError("unsigned receipt slot accepted: "+slot);}catch(IOException expected){checks++;}
            }
            for(int version:new int[]{1,2}) {
                byte[] legacy=Arrays.copyOf(save,version==1?8536437:8536445);ByteBuffer.wrap(legacy).order(ByteOrder.LITTLE_ENDIAN).putLong(8,version);
                Files.write(new File(dir,"pet.state").toPath(),legacy);
                check(PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"))==null,"legacy save cannot invent active receipt");
            }
            Files.write(new File(dir,"pet.state").toPath(),Arrays.copyOf(save,save.length-1));
            try{PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"));throw new AssertionError("truncated save accepted");}catch(IOException expected){checks++;}
            Files.write(new File(dir,"pet.state").toPath(),Arrays.copyOf(save,save.length+1));
            try{PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"));throw new AssertionError("trailing save accepted");}catch(IOException expected){checks++;}
            byte[] wrongLength=save.clone();ByteBuffer.wrap(wrongLength).order(ByteOrder.LITTLE_ENDIAN).putLong(16,Long.MAX_VALUE);
            Files.write(new File(dir,"pet.state").toPath(),wrongLength);
            try{PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"));throw new AssertionError("oversized save accepted");}catch(IOException expected){checks++;}
            byte[] zero=save.clone();Arrays.fill(zero,zero.length-64,zero.length,(byte)0);System.arraycopy(hash(Arrays.copyOfRange(zero,96,zero.length)),0,zero,56,32);
            Files.write(new File(dir,"pet.state").toPath(),zero);check(PetPreviewStore.readCommittedReceipt(new File(dir,"pet.state"))==null,"all-zero schema0 receipt stays neutral");
            try{new PetPreviewSnapshot(a.savePayloadDigest(),7,1800000000000L,a.mode,false,90,0,new int[16384]);throw new AssertionError("transparent image accepted");}catch(IllegalArgumentException expected){checks++;}
            String n=store.pinImage(a); PetPreviewSnapshot next=snapshot(save,8,PetPreviewSnapshot.Mode.PROTECTED,false,0,0,0xff654321);
            String n1=store.pinImage(next);
            check(!n.equals(n1),"changed pixels change resource version");
            check(new PetPreviewStore(dir).readPinnedImage(n)[0]==0xff123456,"N survives publish N+1 and process restart");
            check(store.readPinnedImage(n1)[0]==0xff654321,"N+1 has exact pixels");
            check(n.equals(store.pinImage(snapshot(save,9,PetPreviewSnapshot.Mode.PROTECTED,true,0,0,0xff123456))),"same pixels reuse version despite changed metadata");
            check(store.readPinnedImage("../pet.state")==null,"unavailable version fails safely");
            File pin=new File(dir,"tile-image-"+n+".argb");byte[] image=Files.readAllBytes(pin.toPath());image[10]^=1;Files.write(pin.toPath(),image);
            check(store.readPinnedImage(n)==null,"corrupt pinned resource never returned under old version");
            for(int i=0;i<7;i++)store.pinImage(snapshot(save,7,PetPreviewSnapshot.Mode.NORMAL,false,0,0,0xff000000+i));
            check(dir.listFiles((d,name)->name.startsWith("tile-image-")).length<=4,"resource pin storage is bounded after repeated saves");
            System.out.println("PetPreviewStoreTest: "+checks+" checks passed");
        } finally { for(File f:dir.listFiles()) Files.deleteIfExists(f.toPath());Files.deleteIfExists(dir.toPath()); }
    }
}
