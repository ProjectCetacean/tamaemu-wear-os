package io.github.projectcetacean.tamaemu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/** Acceptance probe: no replacement producer, reader, native envelope or positive pixels. */
public final class PreviewJniProbe {
    private static int checks;
    private static boolean changedFrame;

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }

    private static byte[] checksum(byte[] bytes) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }

    private static int[] render(long handle) throws IOException {
        check(NativeBridge.width(handle) == 128 && NativeBridge.height(handle) == 128,
                "native frame is current 128x128");
        int[] frame = new int[128 * 128];
        NativeBridge.advance(handle, 0, frame);
        boolean opaque = true;
        for (int pixel : frame) opaque &= (pixel >>> 24) == 255;
        check(opaque, "all 16384 rendered native pixels are opaque");
        return frame;
    }

    private static PetPreviewSnapshot snapshot(byte[] hash, long[] life, int[] frame, long savedUtc) {
        return new PetPreviewSnapshot(hash, life[6], savedUtc,
                PetPreviewSnapshot.Mode.values()[(int) life[5]], life[5] == 1,
                life[4], (int) life[7], frame);
    }

    private static void receipt(Path save, byte[] bytes, byte[] nativeHash, long[] life)
            throws Exception {
        check(bytes.length == 8536509, "actual native v3 exact file length");
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(header.getLong(8) == 3 && header.getLong(16) == 8536413,
                "actual native v3 header");
        byte[] expected = checksum(Arrays.copyOfRange(bytes, 96, bytes.length));
        check(Arrays.equals(expected, Arrays.copyOfRange(bytes, 56, 88)),
                "independent SHA256 equals native header checksum");
        check(Arrays.equals(expected, nativeHash), "JNI receipt equals actual payload checksum");
        ByteBuffer trailer = ByteBuffer.wrap(bytes, bytes.length - 64, 64)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int slot = 0; slot < 8; slot++)
            check(trailer.getLong() == life[slot], "exact native trailer slot " + slot);
        PetPreviewStore.CommittedReceipt read = PetPreviewStore.readCommittedReceipt(save.toFile());
        check(read != null, "Java reader accepts native initialized v3");
        check(Arrays.equals(read.payloadDigest(), expected), "Java receipt exact checksum");
        check(read.wallUtcMillis == life[1], "Java receipt exact accounting wall anchor");
        check(read.elapsedRealtimeMillis == life[2], "Java receipt exact elapsed time");
        check(read.bootCount == life[3], "Java receipt exact boot count");
        check(read.debtMillis == life[4], "Java receipt exact debt");
        check(read.mode.ordinal() == life[5], "Java receipt exact mode");
        check(read.generation == life[6], "Java receipt exact generation");
        check(read.clockQuality == life[7], "Java receipt exact clock quality");
        byte[] returnedHash = read.payloadDigest();
        returnedHash[0] ^= 1;
        check(Arrays.equals(read.payloadDigest(), expected), "Java receipt digest detached");
        check(Arrays.equals(Files.readAllBytes(save), bytes), "receipt reader never changes save");
    }

    private static PetPreviewSnapshot verified(PetPreviewStore store, Path save,
            byte[] bytes, PetPreviewStore.State expected) throws Exception {
        PetPreviewStore.Read read = store.readVerified();
        check(read.state == expected, "verified state " + expected);
        check((read.snapshot != null) == (expected == PetPreviewStore.State.VALID),
                "only valid receipt exposes a snapshot");
        check(Arrays.equals(Files.readAllBytes(save), bytes), "cache read never changes native file");
        return read.snapshot;
    }

    private static void sameSnapshot(PetPreviewSnapshot actual, PetPreviewSnapshot expected) {
        check(Arrays.equals(actual.savePayloadDigest(), expected.savePayloadDigest()), "preview exact hash");
        check(Arrays.equals(actual.pixelsArgb(), expected.pixelsArgb()), "preview exact actual raw frame");
        check(actual.committedUtcMillis == expected.committedUtcMillis, "preview exact recorded commit UTC");
        check(actual.generation == expected.generation, "preview exact generation");
        check(actual.mode == expected.mode && actual.awayPaused == expected.awayPaused, "preview exact mode");
        check(actual.debtMillis == expected.debtMillis && actual.clockQuality == expected.clockQuality,
                "preview exact debt and clock quality");
    }

    private static void run(Path dir, String[] args) throws Exception {
        Path rom = dir.resolve("firmware.bin"), save = dir.resolve("pet.state");
        if (args.length == 1) Files.copy(Paths.get(args[0]), rom);
        else {
            byte[] image = new byte[8388608];
            Arrays.fill(image, (byte) 255);
            image[0x400000] = 0; image[0x400001] = 1;
            image[0x400002] = 0x40; image[0x400003] = 2;
            // Same firmware-free hard-halt reset target as LifeJniProbe.
            image[0x400100] = (byte) 0x80; image[0x400101] = 0;
            Files.write(rom, image);
        }
        PetPreviewStore store = new PetPreviewStore(dir.toFile());
        long handle = NativeBridge.open(rom.toString(), save.toString());
        byte[] committed;
        byte[] committedHash;
        long[] committedLife;
        PetPreviewSnapshot current;
        int[] currentFrame;
        try {
            check(Arrays.equals(NativeBridge.readLife(handle), new long[8]), "fresh metadata uninitialized");
            check(NativeBridge.committedPayloadHash(handle) == null, "fresh handle has no commit");
            long[] firstLife = {1, System.currentTimeMillis() - 60000, 12345, Long.MAX_VALUE, 300001, 1, 41, 1};
            NativeBridge.writeLife(handle, firstLife);
            check(Arrays.equals(NativeBridge.readLife(handle), firstLife), "native initialized working metadata exact");
            int[] firstFrame = render(handle);
            boolean firstSaved = NativeBridge.save(handle);
            long firstSavedUtc = System.currentTimeMillis();
            check(firstSaved, "first actual native durable save");
            byte[] firstBytes = Files.readAllBytes(save);
            byte[] firstHash = NativeBridge.committedPayloadHash(handle);
            receipt(save, firstBytes, firstHash, firstLife);
            byte[] callerHash = firstHash.clone();
            int[] callerFrame = firstFrame.clone();
            PetPreviewSnapshot first = snapshot(callerHash, firstLife, callerFrame, firstSavedUtc);
            callerHash[0] ^= 1; callerFrame[0] ^= 1;
            store.publish(first);
            check(Arrays.equals(Files.readAllBytes(save), firstBytes), "publish never changes native file");
            PetPreviewSnapshot read = verified(store, save, firstBytes, PetPreviewStore.State.VALID);
            sameSnapshot(read, snapshot(firstHash, firstLife, firstFrame, firstSavedUtc));
            check(read.committedUtcMillis == firstSavedUtc, "first preview exact successful-save UTC");
            check(read.committedUtcMillis != firstLife[1], "first preview UTC differs from accounting wall anchor");
            byte[] exposedHash = read.savePayloadDigest(); int[] exposedFrame = read.pixelsArgb();
            exposedHash[0] ^= 1; exposedFrame[0] ^= 1;
            sameSnapshot(read, first);
            sameSnapshot(verified(store, save, firstBytes, PetPreviewStore.State.VALID), first);
            String firstVersion = store.pinImage(first);
            check(Arrays.equals(store.readPinnedImage(firstVersion), firstFrame), "pin retains real raw frame");
            int[] pin = store.readPinnedImage(firstVersion); pin[0] ^= 1;
            check(Arrays.equals(store.readPinnedImage(firstVersion), firstFrame), "returned pin is detached");

            // Missing receipt/candidate isolation catches publishing working metadata as durable.
            long[] secondLife = {1, System.currentTimeMillis() - 60000, 12445, 7, 0, 0, 42, 0};
            NativeBridge.advanceHeadless(handle, 100);
            NativeBridge.writeLife(handle, secondLife);
            check(Arrays.equals(NativeBridge.readLife(handle), secondLife), "native working candidate advanced");
            check(Arrays.equals(NativeBridge.committedPayloadHash(handle), firstHash),
                    "working candidate leaves JNI committed receipt unchanged");
            sameSnapshot(verified(store, save, firstBytes, PetPreviewStore.State.VALID), first);
            PetPreviewStore.CommittedReceipt oldReceipt = PetPreviewStore.readCommittedReceipt(save.toFile());
            check(oldReceipt.generation == 41 && oldReceipt.mode == PetPreviewSnapshot.Mode.PROTECTED,
                    "Java receipt stays at committed metadata");

            currentFrame = render(handle);
            boolean secondSaved = NativeBridge.save(handle);
            long secondSavedUtc = System.currentTimeMillis();
            check(secondSaved, "second actual native durable save");
            committed = Files.readAllBytes(save); committedHash = NativeBridge.committedPayloadHash(handle);
            committedLife = secondLife.clone();
            receipt(save, committed, committedHash, committedLife);
            check(!Arrays.equals(firstHash, committedHash), "new native generation changes payload checksum");
            verified(store, save, committed, PetPreviewStore.State.MISMATCH);
            current = snapshot(committedHash, committedLife, currentFrame, secondSavedUtc);
            store.publish(current);
            PetPreviewSnapshot currentRead = verified(store, save, committed, PetPreviewStore.State.VALID);
            sameSnapshot(currentRead, current);
            check(currentRead.committedUtcMillis == secondSavedUtc, "second preview exact successful-save UTC");
            check(currentRead.committedUtcMillis != committedLife[1], "second preview UTC differs from accounting wall anchor");
            check(store.pinImage(snapshot(committedHash, committedLife, firstFrame, secondSavedUtc)).equals(firstVersion),
                    "same actual pixels retain image version across metadata commits");
            String currentVersion = store.pinImage(current);
            changedFrame = !Arrays.equals(firstFrame, currentFrame);
            check(currentVersion.equals(firstVersion) != changedFrame,
                    "actual frame equality alone controls image version");
            check(Arrays.equals(store.readPinnedImage(currentVersion), currentFrame), "current pin exact native frame");

            // Each negative starts from immutable bytes of the successful real native save.
            for (int variant = 0; variant < 3; variant++) {
                byte[] bad = variant == 1 ? Arrays.copyOf(committed, committed.length - 1)
                        : variant == 2 ? Arrays.copyOf(committed, committed.length + 1) : committed.clone();
                if (variant == 0) bad[96] ^= 1;
                Files.write(save, bad);
                boolean rejected = false;
                try { PetPreviewStore.readCommittedReceipt(save.toFile()); }
                catch (IOException expected) { rejected = true; }
                check(rejected, "Java receipt rejects corrupted payload/truncated trailer/appended garbage " + variant);
                verified(store, save, bad, PetPreviewStore.State.UNAVAILABLE);
                Files.write(save, committed);
                verified(store, save, committed, PetPreviewStore.State.VALID);
            }
            for (int variant = 0; variant < 5; variant++) {
                byte[] hash = committedHash.clone(); long[] life = committedLife.clone();
                if (variant == 0) life[6]++;
                if (variant == 1) hash[0] ^= 1;
                if (variant == 2) life[5] = 1;
                if (variant == 3) life[4]++;
                if (variant == 4) life[7] = 1;
                store.publish(snapshot(hash, life, currentFrame, secondSavedUtc));
                verified(store, save, committed, PetPreviewStore.State.MISMATCH);
                store.publish(current);
                verified(store, save, committed, PetPreviewStore.State.VALID);
            }
            Path hiddenRom = dir.resolve("firmware-away.bin");
            Files.move(rom, hiddenRom);
            verified(store, save, committed, PetPreviewStore.State.UNAVAILABLE);
            Files.move(hiddenRom, rom);
            verified(store, save, committed, PetPreviewStore.State.VALID);
            long[] unsaved = committedLife.clone(); unsaved[6]++;
            NativeBridge.writeLife(handle, unsaved);
        } finally { NativeBridge.close(handle); }

        handle = NativeBridge.open(rom.toString(), save.toString());
        try {
            check(Arrays.equals(NativeBridge.readLife(handle), committedLife), "reopen discards unsaved candidate");
            check(Arrays.equals(NativeBridge.committedPayloadHash(handle), committedHash), "reopen exact committed JNI hash");
            receipt(save, committed, NativeBridge.committedPayloadHash(handle), committedLife);
            sameSnapshot(verified(store, save, committed, PetPreviewStore.State.VALID), current);
            check(Arrays.equals(render(handle), currentFrame), "reopen exact actual raw frame");
            check(Arrays.equals(Files.readAllBytes(save), committed), "open/render leaves native commit unchanged");
        } finally { NativeBridge.close(handle); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) throw new IllegalArgumentException("At most one local firmware input");
        Path dir = Files.createTempDirectory(Paths.get(System.getProperty("preview.fixture.root")), "fixture-");
        try {
            run(dir, args);
            System.out.println("PASS: " + checks + " native writer / Java Tile reader checks ("
                    + (args.length == 0 ? "synthetic hard-halt" : "local supplied firmware") + ")");
            System.out.println("Observed real frame change: " + changedFrame
                    + (changedFrame ? "; changed image version verified" : "; equal image version verified"));
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                Path[] owned = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
                for (Path path : owned) Files.delete(path);
            }
        }
    }
}
