package io.github.projectcetacean.tamaemu;

/** Original source frame and metadata of one durable checkpoint. Never a live emulator handle. */
public final class PetPreviewSnapshot {
    public enum Mode { NORMAL, PROTECTED }
    public static final int WIDTH=128, HEIGHT=128, PIXEL_COUNT=WIDTH*HEIGHT;
    public static final long MIN_UTC_MILLIS=946684800000L, MAX_UTC_MILLIS=7258118400000L;
    public final long generation, committedUtcMillis, debtMillis;
    public final Mode mode;
    public final boolean awayPaused;
    public final int clockQuality;
    private final byte[] digest;
    private final int[] pixels;

    public PetPreviewSnapshot(byte[] digest, long generation, long committedUtcMillis, Mode mode,
            boolean awayPaused, long debtMillis, int clockQuality, int[] pixels) {
        if(digest==null || digest.length!=32 || generation<1 ||
                committedUtcMillis<=0 ||
                mode==null || (awayPaused && mode!=Mode.PROTECTED) || debtMillis<0 ||
                clockQuality<0 || clockQuality>1 || pixels==null || pixels.length!=PIXEL_COUNT)
            throw new IllegalArgumentException("Invalid committed preview");
        for(int pixel:pixels) if((pixel>>>24)!=255) throw new IllegalArgumentException("Preview must be opaque");
        this.digest=digest.clone();this.pixels=pixels.clone();this.generation=generation;
        this.committedUtcMillis=committedUtcMillis;this.mode=mode;this.awayPaused=awayPaused;
        this.debtMillis=debtMillis;this.clockQuality=clockQuality;
    }
    public byte[] savePayloadDigest() { return digest.clone(); }
    public int[] pixelsArgb() { return pixels.clone(); }
    public String statusLabel() {
        String label=mode==Mode.NORMAL ? "Normal" : awayPaused ? "Protected: paused away" : "Protected";
        if(debtMillis>0) label+=" · Catch-up needed";
        if(clockQuality!=0 || committedUtcMillis<MIN_UTC_MILLIS || committedUtcMillis>MAX_UTC_MILLIS) label+=" · Time needs checking";
        return label;
    }
    /** Current clock proof overlays presentation only; saved provenance remains historical. */
    String statusLabel(TimeVerifier.Outcome verification) {
        String label=mode==Mode.NORMAL?"Normal":awayPaused?"Protected: paused away":"Protected";
        if(debtMillis>0)label+=" · Catch-up needed";
        return appendTime(label,verification);
    }
    String exceptionalStatus(TimeVerifier.Outcome verification) {
        return appendTime(mode==Mode.NORMAL?null:awayPaused?"Protected: paused away":"Protected",verification);
    }
    private String appendTime(String label,TimeVerifier.Outcome verification) {
        if(committedUtcMillis<MIN_UTC_MILLIS||committedUtcMillis>MAX_UTC_MILLIS)
            label=append(label,"Time needs checking (saved time)");
        String notice=TimeVerifier.notice(verification);
        if(notice!=null)label=append(label,notice);
        if(clockQuality!=0)label=append(label,"Earlier time estimated");
        return label;
    }
    private static String append(String label,String text){return label==null?text:label+" · "+text;}
    /** Saved policy/clock notices only. Checkpoint debt is not current replay progress. */
    public String exceptionalStatus() {
        String label=mode==Mode.NORMAL ? null : awayPaused ? "Protected: paused away" : "Protected";
        if(clockQuality!=0 || committedUtcMillis<MIN_UTC_MILLIS || committedUtcMillis>MAX_UTC_MILLIS)
            label=label==null ? "Time needs checking" : label+" · Time needs checking";
        return label;
    }
}
