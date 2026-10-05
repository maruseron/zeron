package com.maruseron.zeron.analize;

final class SkipResolutionUnit extends RuntimeException {
    SkipResolutionUnit() {
        super(null, null, false, false);
    }
}
