package com.maruseron.zeron.analize;

import com.maruseron.zeron.domain.TypeDescriptor;
import java.util.Optional;

@FunctionalInterface 
public interface TypeProjector {
    Optional<TypeDescriptor> projectTo(final TypeDescriptor source, final TypeDescriptor target);
}
