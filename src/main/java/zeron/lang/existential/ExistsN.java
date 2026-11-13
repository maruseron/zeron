package zeron.lang.existential;

/**
 * Reserved ZEP-0106 holder ABI.
 *
 * <p>The compiler writes this class; application source must not construct or inspect it. Slot
 * order is fixed by the stored source type. The typed {@code Exists1} through {@code Exists3}
 * family can be introduced as an optimization without changing the payload/slot contract.
 */
public final class ExistsN {
    private final Object payload;
    private final Object[] slots;

    private ExistsN(final Object payload, final Object[] slots) {
        this.payload = payload;
        this.slots = slots.clone();
    }

    public static ExistsN of(final Object payload, final Object[] slots) {
        return new ExistsN(payload, slots);
    }

    public Object payload() {
        return payload;
    }

    public Object slot(final int index) {
        return slots[index];
    }

    public int slotCount() {
        return slots.length;
    }
}
