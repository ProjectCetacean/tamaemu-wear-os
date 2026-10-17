package io.github.projectcetacean.tamaemu;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded Android-free preview codec, native-envelope verifier and immutable image pins. */
public final class PetPreviewStore {
    private static final byte[] MAGIC={'T','A','M','A','P','R','E','V'};
    private static final int RECORD_BYTES=8+4+32+8+8+4+4+8+4+4+4+65536+32;
    private static final int SAVE_HEADER=96, V3_PAYLOAD=8536413;
    private static final int IMAGE_BYTES=65536;
    private final File dir;
    public enum State { VALID, MISSING, UNAVAILABLE, MISMATCH }
    public static final class Read {
        public final State state;
        public final PetPreviewSnapshot snapshot;
        private Read(State state,PetPreviewSnapshot snapshot){this.state=state;this.snapshot=snapshot;}
    }
    public static final class CommittedReceipt {
        private final byte[] digest;
        public final long wallUtcMillis, elapsedRealtimeMillis, bootCount, debtMillis, generation;
        public final PetPreviewSnapshot.Mode mode;
        public final int clockQuality;
        private CommittedReceipt(byte[] digest,long[] c) {
            this.digest=digest.clone();wallUtcMillis=c[1];elapsedRealtimeMillis=c[2];bootCount=c[3];
            debtMillis=c[4];mode=PetPreviewSnapshot.Mode.values()[(int)c[5]];generation=c[6];clockQuality=(int)c[7];
        }
        public byte[] payloadDigest(){return digest.clone();}
    }
    public PetPreviewStore(File filesDir) { if(filesDir==null)throw new NullPointerException("filesDir");dir=filesDir; }
    static byte[] digest(byte[] b) { try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);} }
    public static byte[] encode(PetPreviewSnapshot s) throws IOException {
        ByteBuffer b=ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.BIG_ENDIAN);
        b.put(MAGIC).putInt(1).put(s.savePayloadDigest()).putLong(s.generation).putLong(s.committedUtcMillis)
            .putInt(s.mode.ordinal()).putInt(s.awayPaused?1:0).putLong(s.debtMillis).putInt(s.clockQuality)
            .putInt(128).putInt(128);
        for(int p:s.pixelsArgb()) b.putInt(p);
        b.put(digest(Arrays.copyOf(b.array(),RECORD_BYTES-32)));return b.array();
    }
    public static PetPreviewSnapshot decode(byte[] encoded) throws IOException {
        if(encoded==null || encoded.length!=RECORD_BYTES) throw new IOException("Invalid preview length");
        byte[] expected=Arrays.copyOfRange(encoded,RECORD_BYTES-32,RECORD_BYTES);
        if(!MessageDigest.isEqual(expected,digest(Arrays.copyOf(encoded,RECORD_BYTES-32)))) throw new IOException("Preview checksum mismatch");
        ByteBuffer b=ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);byte[] magic=new byte[8];b.get(magic);
        if(!Arrays.equals(magic,MAGIC) || b.getInt()!=1) throw new IOException("Unknown preview schema");
        byte[] d=new byte[32];b.get(d);long g=b.getLong(),t=b.getLong();int mode=b.getInt(),paused=b.getInt();
        long debt=b.getLong();int quality=b.getInt(),width=b.getInt(),height=b.getInt();
        if(width!=128 || height!=128 || mode<0 || mode>1 || paused<0 || paused>1) throw new IOException("Invalid preview metadata");
        int[] pixels=new int[16384];for(int i=0;i<pixels.length;i++)pixels[i]=b.getInt();
        try{return new PetPreviewSnapshot(d,g,t,PetPreviewSnapshot.Mode.values()[mode],paused==1,debt,quality,pixels);}
        catch(IllegalArgumentException e){throw new IOException("Invalid preview metadata",e);}
    }
    private static byte[] readExact(File file,int length) throws IOException {
        try(InputStream in=new BufferedInputStream(new FileInputStream(file))) {
            byte[] out=new byte[length];int off=0;
            while(off<length) {int n=in.read(out,off,length-off);if(n<0)throw new EOFException();off+=n;}
            if(in.read()!=-1)throw new IOException("Trailing bytes");return out;
        }
    }
    private static void atomicWrite(File target,byte[] data) throws IOException {
        File parent=target.getParentFile();if(!parent.isDirectory())throw new IOException("Private directory unavailable");
        File temp=File.createTempFile("tile-", ".tmp",parent);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);out.flush();out.getFD().sync();}
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(temp.toPath());}
    }
    public void publish(PetPreviewSnapshot snapshot) throws IOException { atomicWrite(new File(dir,"pet-preview.cache"),encode(snapshot)); }
    /** Returns no receipt for legacy or uninitialized state. Hashes all guest/NOR/pacing/clock bytes. */
    public static CommittedReceipt readCommittedReceipt(File save) throws IOException {
        try(InputStream in=new BufferedInputStream(new FileInputStream(save))) {
            byte[] h=new byte[SAVE_HEADER];readFully(in,h);ByteBuffer header=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic=new byte[8];header.get(magic);long version=header.getLong(),length=header.getLong();
            if(!Arrays.equals(magic,new byte[]{'T','A','M','A','W','E','A','R'}) ||
                    !Arrays.equals(Arrays.copyOfRange(h,88,96),new byte[]{'i','d','l',0,0,0,0,0}))throw new IOException("Invalid native envelope");
            if(version==1 || version==2) return null;
            if(version!=3 || length!=V3_PAYLOAD)throw new IOException("Invalid native payload size/schema");
            MessageDigest md;
            try {md=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
            byte[] buffer=new byte[16384];int remaining=V3_PAYLOAD-64;
            while(remaining>0){int n=in.read(buffer,0,Math.min(remaining,buffer.length));if(n<0)throw new EOFException();md.update(buffer,0,n);remaining-=n;}
            byte[] clock=new byte[64];readFully(in,clock);md.update(clock);
            if(in.read()!=-1)throw new IOException("Trailing save bytes");
            byte[] actual=md.digest();if(!MessageDigest.isEqual(actual,Arrays.copyOfRange(h,56,88)))throw new IOException("Native payload checksum mismatch");
            ByteBuffer receipt=ByteBuffer.wrap(clock).order(ByteOrder.LITTLE_ENDIAN);long[] c=new long[8];
            for(int i=0;i<c.length;i++){c[i]=receipt.getLong();if(c[i]<0)throw new IOException("Unsigned clock outside Java range");}
            if(c[0]==0) {for(long value:c)if(value!=0)throw new IOException("Malformed uninitialized clock");return null;}
            if(c[0]!=1 || c[1]<=0 ||
                    c[5]>1 || c[6]<1 || c[7]>1)throw new IOException("Invalid native clock receipt");
            return new CommittedReceipt(actual,c);
        }
    }
    private static void readFully(InputStream in,byte[] out)throws IOException {
        int off=0;while(off<out.length){int n=in.read(out,off,out.length-off);if(n<0)throw new EOFException();off+=n;}
    }
    public Read readVerified() {
        File preview=new File(dir,"pet-preview.cache");if(!preview.exists())return new Read(State.MISSING,null);
        try {
            PetPreviewSnapshot s=decode(readExact(preview,RECORD_BYTES));
            if(!new File(dir,"firmware.bin").isFile())return new Read(State.UNAVAILABLE,null);
            CommittedReceipt c=readCommittedReceipt(new File(dir,"pet.state"));
            if(c==null)return new Read(State.UNAVAILABLE,null);
            if(!MessageDigest.isEqual(s.savePayloadDigest(),c.payloadDigest()) || s.generation!=c.generation ||
                    s.mode!=c.mode || s.debtMillis!=c.debtMillis || s.clockQuality!=c.clockQuality)
                return new Read(State.MISMATCH,null);
            return new Read(State.VALID,s);
        }catch(IOException|IllegalArgumentException|SecurityException e){return new Read(State.UNAVAILABLE,null);}
    }
    private static byte[] imageBytes(PetPreviewSnapshot s) {
        ByteBuffer b=ByteBuffer.allocate(IMAGE_BYTES);for(int p:s.pixelsArgb())b.putInt(p);return b.array();
    }
    private static String hex(byte[] d) {StringBuilder s=new StringBuilder(64);for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    /** Pins before advertising. Same image gets the same version even if saved metadata changes. */
    public synchronized String pinImage(PetPreviewSnapshot snapshot) throws IOException {
        byte[] bytes=imageBytes(snapshot);String version=hex(digest(bytes));File pin=new File(dir,"tile-image-"+version+".argb");
        atomicWrite(pin,bytes);
        File[] pins=dir.listFiles((d,name)->name.matches("tile-image-[0-9a-f]{64}\\.argb") && !name.equals(pin.getName()));
        if(pins!=null && pins.length>3) {
            Arrays.sort(pins,Comparator.comparingLong(File::lastModified).reversed());
            for(int i=3;i<pins.length;i++)Files.deleteIfExists(pins[i].toPath());
        }
        return version;
    }
    /** Unknown/evicted/corrupt versions fail closed; never substitute the newest image. */
    public int[] readPinnedImage(String version) throws IOException {
        if(version==null || !version.matches("[0-9a-f]{64}"))return null;
        File pin=new File(dir,"tile-image-"+version+".argb");if(!pin.isFile())return null;
        byte[] bytes;
        try{bytes=readExact(pin,IMAGE_BYTES);}catch(IOException e){return null;}
        if(!version.equals(hex(digest(bytes))))return null;
        int[] pixels=new int[16384];ByteBuffer b=ByteBuffer.wrap(bytes);
        for(int i=0;i<pixels.length;i++){pixels[i]=b.getInt();if((pixels[i]>>>24)!=255)return null;}
        return pixels;
    }
}
