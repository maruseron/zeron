package zeron.runtime;

/** The carrier a Zeron `raise` throws, exposed so runtime support can inspect it. */
public interface EffectCarrier {
    Object payload();
}
