package com.authswitch.iso;

import java.util.Collections;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable ISO 8583 message: a 4-digit MTI plus a sparse set of numbered data elements (DE2..DE64).
 * Built with the Builder pattern so a message can never be observed half-constructed.
 */
public final class Iso8583Message {
    private final String mti;
    private final SortedMap<Integer, String> fields;

    private Iso8583Message(Builder b) {
        this.mti = b.mti;
        this.fields = Collections.unmodifiableSortedMap(new TreeMap<>(b.fields));
    }

    public String mti() {
        return mti;
    }

    public Optional<String> field(int de) {
        return Optional.ofNullable(fields.get(de));
    }

    public SortedMap<Integer, String> fields() {
        return fields;
    }

    public static Builder builder(String mti) {
        return new Builder(mti);
    }

    /** Used to turn a 0100 request into its 0110 response without mutating the original. */
    public Builder toBuilder() {
        Builder b = new Builder(mti);
        b.fields.putAll(fields);
        return b;
    }

    @Override
    public String toString() {
        // Deliberately prints field numbers only, never values (PAN must not leak into logs).
        return "Iso8583Message{mti=" + mti + ", fields=" + fields.keySet() + "}";
    }

    public static final class Builder {
        private String mti;
        private final TreeMap<Integer, String> fields = new TreeMap<>();

        private Builder(String mti) {
            mti(mti);
        }

        public Builder mti(String mti) {
            if (mti == null || !mti.matches("\\d{4}")) {
                throw new IllegalArgumentException("MTI must be 4 digits");
            }
            this.mti = mti;
            return this;
        }

        public Builder field(int de, String value) {
            if (de < 2 || de > 64) {
                throw new IllegalArgumentException("Only DE2..DE64 supported (no secondary bitmap): " + de);
            }
            if (value == null) {
                throw new IllegalArgumentException("DE" + de + " value is null");
            }
            fields.put(de, value);
            return this;
        }

        public Iso8583Message build() {
            return new Iso8583Message(this);
        }
    }
}
