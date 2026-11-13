package zeron.lang.existential;

/** Reserved ZEP-0106 holder for three hidden slots. */
public final class Exists3<P, S1, S2, S3> {
    private final P payload;
    private final S1 slot0;
    private final S2 slot1;
    private final S3 slot2;

    private Exists3(final P payload, final S1 slot0, final S2 slot1, final S3 slot2) {
        this.payload = payload;
        this.slot0 = slot0;
        this.slot1 = slot1;
        this.slot2 = slot2;
    }

    public static <P, S1, S2, S3> Exists3<P, S1, S2, S3> of(final P payload,
                                                             final S1 slot0,
                                                             final S2 slot1,
                                                             final S3 slot2) {
        return new Exists3<>(payload, slot0, slot1, slot2);
    }

    public P payload() {
        return payload;
    }

    public S1 slot0() {
        return slot0;
    }

    public S2 slot1() {
        return slot1;
    }

    public S3 slot2() {
        return slot2;
    }
}
