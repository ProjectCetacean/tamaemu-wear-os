package io.github.projectcetacean.tamaemu;

/** Pure UI contract tests: no preference or working candidate can select a life mode. */
public final class LifeUiModelTest {
    public static void main(String[] args) {
        LifeUiModel ui = new LifeUiModel();
        check(!ui.careEnabled() && !ui.modeEnabled() && ui.selectedMode() == -1, "unsettled startup blocks care and mode");
        ui.update(status(0, 0, 0, LifeStatus.OwnerState.PLAYING, 1, 0));
        check(!ui.careEnabled() && !ui.modeEnabled(), "old playing status cannot admit input before foreground is ready");
        ui.setForegroundReady(true);
        check(ui.careEnabled() && ui.modeEnabled() && ui.selectedMode() == 0, "settled committed mode enables care");
        ui.update(status(0,0,173,LifeStatus.OwnerState.PLAYING,1,0));
        check(ui.careEnabled()&&!ui.modeEnabled(),"ordinary foreground backlog must not interrupt care");
        ui.update(status(0,0,0,LifeStatus.OwnerState.PLAYING,1,0));
        long request = ui.beginModeChange();
        check(request != 0 && !ui.careEnabled() && !ui.modeEnabled(), "mode request immediately releases care admission");
        ui.update(status(1, 1, 0, LifeStatus.OwnerState.SAVING, 2, 0));
        check(ui.selectedMode() == 0, "even committed status cannot acknowledge pending selection before callback");
        ui.modeResult(request, false, status(1, 0, 0, LifeStatus.OwnerState.SAVE_FAILED, 1, 0));
        check(ui.selectedMode() == 0 && ui.modeRejected() && !ui.careEnabled(), "failed candidate does not select Protected");
        ui.update(status(0, 0, 0, LifeStatus.OwnerState.PLAYING, 1, 0));
        request = ui.beginModeChange();
        ui.update(status(1, 0, 0, LifeStatus.OwnerState.CATCHING_UP, 1, 0));
        check(ui.selectedMode() == 0, "working candidate never selects Protected");
        ui.modeResult(request, true, status(1, 1, 0, LifeStatus.OwnerState.CATCHING_UP, 2, 0));
        check(ui.selectedMode() == 1 && !ui.careEnabled(), "true commit selects mode but catchup remains blocked");
        ui.update(status(1, 1, 0, LifeStatus.OwnerState.PLAYING, 2, 0));
        check(ui.careEnabled() && ui.modeEnabled() && !ui.modeRejected(), "settled callback completion re-enables care");
        ui.setForegroundReady(false);
        check(!ui.careEnabled() && !ui.modeEnabled() && ui.selectedMode() == 1, "pause blocks actions while retaining committed selection");
        ui.setForegroundReady(true);
        ui.modeResult(request, false, status(0, 0, 0, LifeStatus.OwnerState.SAVE_FAILED, 1, 0));
        check(ui.selectedMode() == 1 && ui.careEnabled(), "duplicate stale callback cannot undo committed selection");
        for (LifeStatus.OwnerState state : LifeStatus.OwnerState.values()) {
            ui.update(status(1, 1, 0, state, 2, 0));
            check(ui.careEnabled() == (state == LifeStatus.OwnerState.PLAYING), "owner state admission " + state);
            if (state != LifeStatus.OwnerState.PLAYING) check(ui.beginModeChange() == 0, "blocked mode request " + state);
        }
        ui.update(status(1, 1, 125, LifeStatus.OwnerState.PLAYING, 2, 1));
        check(ui.careEnabled() && !ui.modeEnabled() && ui.status().clockQuality == 1, "foreground debt preserves care and committed mode guard");
        check(LifeUiModel.duration(1).equals("0h 0m 1s"), "positive subsecond debt never displayed as zero");
        check(LifeUiModel.duration(3_661_001).equals("1h 1m 2s"), "duration rounds remaining debt up");
        check(LifeUiModel.duration(Long.MAX_VALUE).equals("2562047788015h 12m 56s"), "large debt formatting does not overflow");
        System.out.println("LifeUiModelTest: committed selection, callback ordering, owner admission and debt formatting passed");
    }
    static LifeStatus status(long workingMode, long committedMode, long debt, LifeStatus.OwnerState state, long generation, long quality) {
        LifeRecord working = LifeRecord.from(new long[]{1,10000,1000,1,debt,workingMode,generation,quality});
        LifeRecord committed = LifeRecord.from(new long[]{1,10000,1000,1,0,committedMode,generation,quality});
        return new LifeStatus(working,committed,state,10000,"Background progress requested",false);
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
}
