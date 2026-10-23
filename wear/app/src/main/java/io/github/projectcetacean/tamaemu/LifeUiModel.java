package io.github.projectcetacean.tamaemu;

/** Activity-local presentation only. The native committed ledger remains authoritative. */
final class LifeUiModel {
    private LifeStatus status;
    private int selected = -1;
    private long requestSequence, pendingRequest;
    private boolean rejected, foregroundReady;
    LifeStatus status() { return status; }
    void setForegroundReady(boolean ready) { foregroundReady = ready; }
    void update(LifeStatus next) {
        if (next == null) return;
        status = next;
        if (pendingRequest == 0 && next.committedGeneration > 0) selected = (int) next.mode;
    }
    int selectedMode() { return selected; }
    boolean careEnabled() {
        return foregroundReady && pendingRequest == 0 && status != null
                && (status.state == LifeStatus.OwnerState.PLAYING || status.inputContinuity);
    }
    boolean modeEnabled() { return careEnabled() && selected >= 0 && status.debtMs == 0 && !status.saving; }
    boolean modeRejected() { return rejected; }
    boolean modePending() { return pendingRequest != 0; }
    long beginModeChange() {
        if (!modeEnabled()) return 0;
        rejected = false;
        pendingRequest = ++requestSequence;
        return pendingRequest;
    }
    void modeResult(long request, boolean accepted, LifeStatus next) {
        if (pendingRequest == 0 || request != pendingRequest) return;
        pendingRequest = 0;
        if (next != null) status = next;
        if (accepted && next != null && next.committedGeneration > 0) selected = (int) next.mode;
        rejected = !accepted;
    }
    static String duration(long millis) {
        long seconds = millis / 1000 + (millis % 1000 > 0 ? 1 : 0);
        return seconds / 3600 + "h " + seconds / 60 % 60 + "m " + seconds % 60 + "s";
    }
}
