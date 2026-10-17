package com.authswitch.iso;

import java.util.Map;

/**
 * Stateless, thread-safe codec for a simplified ASCII ISO 8583 dialect:
 * <pre>  MTI(4) | primary bitmap (16 hex chars) | data elements in ascending order</pre>
 * Real networks use binary/EBCDIC encodings and secondary bitmaps; what this models is the
 * bitmap-driven structure, fixed vs LLVAR fields, and strict length/charset checking.
 */
public final class Iso8583Codec {

    private enum Kind { FIXED, LLVAR }

    private record Spec(Kind kind, int length, boolean numeric) {}

    private static final Map<Integer, Spec> SPECS = Map.ofEntries(
            Map.entry(2, new Spec(Kind.LLVAR, 19, true)),   // PAN
            Map.entry(3, new Spec(Kind.FIXED, 6, true)),    // processing code
            Map.entry(4, new Spec(Kind.FIXED, 12, true)),   // amount, minor units
            Map.entry(7, new Spec(Kind.FIXED, 10, true)),   // transmission date/time MMDDhhmmss
            Map.entry(11, new Spec(Kind.FIXED, 6, true)),   // STAN
            Map.entry(14, new Spec(Kind.FIXED, 4, true)),   // expiry YYMM
            Map.entry(37, new Spec(Kind.FIXED, 12, false)), // retrieval reference number
            Map.entry(38, new Spec(Kind.FIXED, 6, false)),  // authorization id
            Map.entry(39, new Spec(Kind.FIXED, 2, false)),  // response code
            Map.entry(41, new Spec(Kind.FIXED, 8, false)),  // terminal id
            Map.entry(49, new Spec(Kind.FIXED, 3, true)));  // currency

    public String encode(Iso8583Message msg) throws MessageFormatException {
        long bitmap = 0L;
        StringBuilder body = new StringBuilder();
        for (Map.Entry<Integer, String> e : msg.fields().entrySet()) {
            int de = e.getKey();
            String value = e.getValue();
            Spec spec = SPECS.get(de);
            if (spec == null) throw new MessageFormatException("Unsupported DE" + de);
            validate(de, spec, value);
            bitmap |= 1L << (64 - de);
            if (spec.kind() == Kind.LLVAR) body.append(String.format("%02d", value.length()));
            body.append(value);
        }
        return msg.mti() + String.format("%016X", bitmap) + body;
    }

    public Iso8583Message decode(String wire) throws MessageFormatException {
        if (wire == null || wire.length() < 20) throw new MessageFormatException("Message too short");
        String mti = wire.substring(0, 4);
        if (!mti.matches("\\d{4}")) throw new MessageFormatException("Bad MTI");
        long bitmap;
        try {
            bitmap = Long.parseUnsignedLong(wire.substring(4, 20), 16);
        } catch (NumberFormatException ex) {
            throw new MessageFormatException("Bad bitmap");
        }
        if ((bitmap & (1L << 63)) != 0) throw new MessageFormatException("Secondary bitmap not supported");

        Iso8583Message.Builder b = Iso8583Message.builder(mti);
        int pos = 20;
        for (int de = 2; de <= 64; de++) {
            if ((bitmap & (1L << (64 - de))) == 0) continue;
            Spec spec = SPECS.get(de);
            if (spec == null) throw new MessageFormatException("Unsupported DE" + de);
            int len = spec.length();
            if (spec.kind() == Kind.LLVAR) {
                if (pos + 2 > wire.length()) throw new MessageFormatException("Truncated length of DE" + de);
                try {
                    len = Integer.parseInt(wire.substring(pos, pos + 2));
                } catch (NumberFormatException ex) {
                    throw new MessageFormatException("Bad length prefix of DE" + de);
                }
                pos += 2;
                if (len < 1 || len > spec.length()) throw new MessageFormatException("DE" + de + " length out of range");
            }
            if (pos + len > wire.length()) throw new MessageFormatException("Truncated DE" + de);
            String value = wire.substring(pos, pos + len);
            pos += len;
            validate(de, spec, value);
            b.field(de, value);
        }
        if (pos != wire.length()) throw new MessageFormatException("Trailing bytes after last field");
        return b.build();
    }

    private static void validate(int de, Spec spec, String value) throws MessageFormatException {
        boolean badLength = spec.kind() == Kind.FIXED
                ? value.length() != spec.length()
                : value.isEmpty() || value.length() > spec.length();
        if (badLength) throw new MessageFormatException("DE" + de + " has invalid length " + value.length());
        if (spec.numeric() && !value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new MessageFormatException("DE" + de + " must be numeric");
        }
        if (!spec.numeric() && !value.chars().allMatch(c -> c > 31 && c < 127)) {
            throw new MessageFormatException("DE" + de + " must be printable ASCII");
        }
    }
}
