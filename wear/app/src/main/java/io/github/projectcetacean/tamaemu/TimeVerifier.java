package io.github.projectcetacean.tamaemu;

/** Process-local scheduling tolerance, never a certificate of saved or guest time. */
final class TimeVerifier {
    enum Outcome { MATCH, MISMATCH, UNAVAILABLE }
    interface Provider { java.time.Clock obtain(); }
    static final long TOLERANCE_MS=5000, MAX_SPAN_MS=250, CONTINUITY_MS=1000,
            REUSE_MS=15*60*1000, RETRY_MS=60*1000;
    private final Object comparisons=new Object();
    private final Clock local;
    private final Provider provider;
    private Outcome outcome=Outcome.UNAVAILABLE;
    private ClockSample anchor,observed,attempt;

    TimeVerifier(Clock local,Provider provider){this.local=local;this.provider=provider;}

    /** Only cheap local samples on presentation; invalidation is irreversible until a new check. */
    synchronized Outcome current() {
        try { return validate(local.sample()); }
        catch(RuntimeException unavailable){return invalidate();}
    }
    private Outcome invalidate(){anchor=null;observed=null;return outcome=Outcome.UNAVAILABLE;}
    private Outcome validate(ClockSample now) {
        if(anchor==null)return invalidate();
        if(!continuous(anchor,now)||!continuous(observed,now)
                ||anchor.bootCount==ClockSample.UNKNOWN_BOOT
                ||Math.subtractExact(now.elapsedMs,anchor.elapsedMs)>=REUSE_MS)return invalidate();
        observed=now;
        return outcome;
    }
    private static boolean continuous(ClockSample before,ClockSample after) {
        if(before==null||after==null||before.bootCount!=after.bootCount||after.elapsedMs<before.elapsedMs)return false;
        long elapsed=Math.subtractExact(after.elapsedMs,before.elapsedMs);
        long difference=Math.subtractExact(Math.subtractExact(after.wallMs,before.wallMs),elapsed);
        return difference>=-CONTINUITY_MS&&difference<=CONTINUITY_MS;
    }
    /** Called opportunistically on serial background work, never capture/save or frame work. */
    Outcome check() {
        synchronized(comparisons) {
            try {
                ClockSample before;
                synchronized(this) {
                    before=local.sample();
                    Outcome reusable=validate(before);
                    if(attempt!=null&&before!=null&&attempt.bootCount==before.bootCount
                            &&before.elapsedMs>=attempt.elapsedMs
                            &&Math.subtractExact(before.elapsedMs,attempt.elapsedMs)<RETRY_MS)return reusable;
                    if(before==null)return invalidate();
                    attempt=before;
                    // A failed fresh comparison must never leave an old MATCH usable.
                    invalidate();
                }
                // Presentation does not acquire this comparison monitor or wait for the provider.
                java.time.Clock network=provider.obtain();
                if(network==null)return Outcome.UNAVAILABLE;
                long utc=network.millis();
                ClockSample after=local.sample();
                synchronized(this) {
                    if(utc<=0||!continuous(before,after))return invalidate();
                    long span=Math.subtractExact(after.elapsedMs,before.elapsedMs);
                    if(span>MAX_SPAN_MS)return invalidate();
                    // The read lies within the bracket. Use its elapsed midpoint, bounded to 250 ms.
                    long wallAtRead=Math.addExact(before.wallMs,span/2);
                    long offset=Math.subtractExact(utc,wallAtRead);
                    outcome=offset>=-TOLERANCE_MS&&offset<=TOLERANCE_MS?Outcome.MATCH:Outcome.MISMATCH;
                    anchor=after;observed=after;
                    // UNKNOWN_BOOT supports this return value only; current() cannot reuse it.
                    return outcome;
                }
            } catch(RuntimeException unavailable){synchronized(this){return invalidate();}}
        }
    }
    static String notice(Outcome outcome) {
        if(outcome==Outcome.MATCH)return null;
        return outcome==Outcome.MISMATCH?"Watch time differs from network time":"Automatic time verification unavailable";
    }
}
