package zeron.runtime;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;

public final class LambdaTemplateSupport {
    private final MethodHandle factory;
    private final Object[] captures;
    private final Map<List<Object>, Object> specializations = new HashMap<>();

    public LambdaTemplateSupport(final MethodHandle factory, final Object[] captures) {
        this.factory = Objects.requireNonNull(factory);
        this.captures = Objects.requireNonNull(captures).clone();
    }

    public Object specialize(final String key, final MethodHandle[] evidence) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(evidence);
        final var cacheKey = new ArrayList<Object>(evidence.length + 1);
        cacheKey.add(key);
        cacheKey.addAll(List.of(evidence.clone()));
        final var immutableKey = List.copyOf(cacheKey);
        synchronized (specializations) {
            final var cached = specializations.get(immutableKey);
            if (cached != null) return cached;
            final Object specialized;
            try {
                specialized = (Object) factory.invokeExact(captures, evidence.clone());
            } catch (final RuntimeException | Error failure) {
                throw failure;
            } catch (final Throwable failure) {
                throw new IllegalStateException("Could not specialize a constrained lambda.", failure);
            }
            specializations.put(immutableKey, specialized);
            return specialized;
        }
    }
}
