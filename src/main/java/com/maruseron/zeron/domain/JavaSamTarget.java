package com.maruseron.zeron.domain;

import java.lang.constant.MethodTypeDesc;
import java.util.Objects;

/**
 * The resolved Java functional-interface target of a Zeron lambda or top-level function reference.
 *
 * <p>The interface type is the source-level Zeron view of the Java parameter. The erased method
 * type is the interface's class-file descriptor; the instantiated method type substitutes the
 * interface's type arguments. The function type is the Zeron signature used to resolve the
 * callback body.</p>
 */
public record JavaSamTarget(TypeDescriptor interfaceType,
                            String methodName,
                            MethodTypeDesc erasedMethodType,
                            MethodTypeDesc instantiatedMethodType,
                            FunctionDescriptor functionType) {
    public JavaSamTarget {
        Objects.requireNonNull(interfaceType);
        Objects.requireNonNull(methodName);
        Objects.requireNonNull(erasedMethodType);
        Objects.requireNonNull(instantiatedMethodType);
        Objects.requireNonNull(functionType);
    }
}
