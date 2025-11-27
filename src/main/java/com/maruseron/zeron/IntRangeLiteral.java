package com.maruseron.zeron;

import com.maruseron.zeron.scan.Token;

import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.function.IntConsumer;

public record IntRangeLiteral(int start, Token operator, int end, int step)
        implements Iterable<Integer> {
    public IntRangeLiteral {
        if (step == 0)
            throw new IllegalArgumentException("Step must be a non zero integer.");
    }

    public IntRangeLiteral(int start, Token operator, int end) {
        this(start, operator, end, start <= end ? 1 : -1);
    }

    @Override
    public Iterator iterator() {
        return new Iterator();
    }

    @Override
    public String toString() {
        return "Range[" + start + " - " + end + " step " + step + "]";
    }

    public class Iterator implements PrimitiveIterator<Integer, IntConsumer> {
        private int current     = IntRangeLiteral.this.start;
        private final int end   = IntRangeLiteral.this.end;
        private final int step  = IntRangeLiteral.this.step;

        @Override
        public boolean hasNext() {
            // if step is positive, there is a next if current is less    than end.
            //           otherwise, there is a next if current is greater than end.
            return step > 0 ? current <= end :
                              current >= end ;
        }

        @Override
        public Integer next() {
            if (hasNext())
                try     { return current;  }
                finally { current += step; }

            throw new NoSuchElementException();
        }

        @Override
        public void forEachRemaining(IntConsumer action) {
            while (hasNext()) {
                action.accept(next());
            }
        }
    }
}
