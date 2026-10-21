package zeron.runtime;

import java.math.BigInteger;

public final class BigIntegerSupport {
    private BigIntegerSupport() {}

    public static BigInteger fromLiteralText(final String text) {
        final var source = text.replace("_", "");
        final var sign = source.startsWith("-") || source.startsWith("+") ? source.substring(0, 1) : "";
        final var unsigned = sign.isEmpty() ? source : source.substring(1);
        final var radix = unsigned.length() > 2 && unsigned.charAt(0) == '0'
                ? switch (Character.toLowerCase(unsigned.charAt(1))) {
                    case 'b' -> 2;
                    case 'o' -> 8;
                    case 'x' -> 16;
                    default -> 10;
                }
                : 10;
        final var digits = radix == 10 ? unsigned : unsigned.substring(2);
        return new BigInteger(sign + digits, radix);
    }
}
