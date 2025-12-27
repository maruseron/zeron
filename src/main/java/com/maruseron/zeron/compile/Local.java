package com.maruseron.zeron.compile;

import com.maruseron.zeron.domain.TypeDescriptor;

public record Local(TypeDescriptor type, int depth) {}
