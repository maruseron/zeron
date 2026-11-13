package zeron.lang.existential;

/** Reserved ZEP-0106 holder for two hidden slots. */
public final class Exists2<P, S1, S2> {
    private final P payload;
    private final S1 slot0;
    private final S2 slot1;

    private Exists2(final P payload, final S1 slot0, final S2 slot1) {
        this.payload = payload;
        this.slot0 = slot0;
        this.slot1 = slot1;
    }

    public static <P, S1, S2> Exists2<P, S1, S2> of(final P payload, final S1 slot0, final S2 slot1) {
        return new Exists2<>(payload, slot0, slot1);
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
}
