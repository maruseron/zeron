package zeron.lang.existential;

/** Reserved ZEP-0106 holder for one hidden slot. */
public final class Exists1<P, S1> {
    private final P payload;
    private final S1 slot0;

    private Exists1(final P payload, final S1 slot0) {
        this.payload = payload;
        this.slot0 = slot0;
    }

    public static <P, S1> Exists1<P, S1> of(final P payload, final S1 slot0) {
        return new Exists1<>(payload, slot0);
    }

    public P payload() {
        return payload;
    }

    public S1 slot0() {
        return slot0;
    }
}
