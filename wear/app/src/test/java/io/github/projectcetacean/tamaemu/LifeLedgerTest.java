package io.github.projectcetacean.tamaemu;

/** Literal clock/debt expectations, independent of native or wall pacing. */
public final class LifeLedgerTest {
    static int checks;
    static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    static void rejects(Runnable action) { boolean rejected=false; try { action.run(); } catch (IllegalArgumentException | ArithmeticException expected) { rejected=true; } check(rejected,"invalid/overflow rejected"); }
    static ClockSample at(long wall,long elapsed,long boot) { return new ClockSample(wall,elapsed,boot); }
    public static void main(String[] args) {
        LifeRecord initial=LifeLedger.initialize(at(10000,1000,4));
        check(initial.debtMs==0 && initial.generation==1 && initial.mode==0,"migration has zero historical debt and Normal generation1");
        LifeRecord sleep=LifeLedger.account(initial,at(15000,6000,4),false);
        check(sleep.debtMs==5000 && sleep.clockQuality==0,"sameboot sleep accrues fully");
        LifeRecord edit=LifeLedger.account(sleep,at(9000,11000,4),false);
        check(edit.debtMs==10000 && edit.clockQuality==1 && edit.wallUtcMs==9000,"wall rollback cannot erase monotonic life");
        LifeRecord old=LifeLedger.account(edit,at(8000,10000,4),true);
        check(old.debtMs==10000 && old.elapsedMs==10000 && old.clockQuality==1,"genuine monotonic regression uses nonnegative UTC and reanchors");
        LifeRecord regression=LifeLedger.account(initial,at(13000,200,4),false);
        check(regression.debtMs==3000 && regression.elapsedMs==200 && regression.clockQuality==1,"forward UTC monotonic regression fallback");
        LifeRecord reboot=LifeLedger.account(edit,at(19000,100,5),false);
        check(reboot.debtMs==20000 && reboot.elapsedMs==100 && reboot.clockQuality==1,"reboot uses UTC");
        LifeRecord unknown=LifeLedger.account(initial,at(12000,9000,ClockSample.UNKNOWN_BOOT),false);
        check(unknown.debtMs==2000 && unknown.clockQuality==1,"unknown boot uses UTC not elapsed");
        LifeRecord rollback=LifeLedger.account(initial,at(9000,20,5),false);
        check(rollback.debtMs==0 && rollback.wallUtcMs==9000 && rollback.clockQuality==1,"crossboot rollback reanchors without negative debt");
        LifeRecord protectedRecord=LifeLedger.mode(initial,true);
        LifeRecord away=LifeLedger.account(protectedRecord,at(15000,6000,4),false);
        check(away.debtMs==0 && away.elapsedMs==6000,"Protected away refreshes anchors");
        LifeRecord stall=LifeLedger.account(away,at(25000,16000,4),true);
        check(stall.debtMs==10000,"Protected foreground retains entire stall");
        check(LifeLedger.consume(stall,100).debtMs==9900,"consume only requested quantum");
        check(LifeLedger.account(initial,at(10000000000L,9999991000L,4),false).debtMs==9999990000L,"large interval uncapped");
        check(LifeLedger.account(initial,at(12001,2000,4),false).clockQuality==1,"wall inconsistency tolerance1000");
        check(LifeLedger.account(initial,at(12000,2000,4),false).clockQuality==0,"tolerance inclusive");
        rejects(()->at(0,1,1)); rejects(()->at(1,-1,1)); rejects(()->at(1,0,-1));
        rejects(()->LifeRecord.from(new long[]{0,0,0,0,1,0,0,0}));
        rejects(()->LifeRecord.from(new long[]{1,1,0,0,0,2,1,0}));
        LifeRecord full=LifeRecord.from(new long[]{1,10000,1000,4,Long.MAX_VALUE,0,1,0});
        rejects(()->LifeLedger.account(full,at(10001,1001,4),false));
        rejects(()->LifeLedger.nextGeneration(LifeRecord.from(new long[]{1,1,0,0,0,0,Long.MAX_VALUE,0})));
        rejects(()->LifeLedger.consume(stall,101)); rejects(()->LifeLedger.consume(initial,1));
        System.out.println("LifeLedgerTest: "+checks+" checks passed");
    }
}
