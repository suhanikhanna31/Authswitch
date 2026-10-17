package com.authswitch.validation;

import com.authswitch.model.AuthRequest;
import com.authswitch.model.PanUtil;
import com.authswitch.model.ResponseCode;

import java.time.Clock;
import java.time.YearMonth;
import java.util.Optional;

/** Concrete handlers plus the standard chain used by the switch. */
public final class Validators {
    private Validators() {}

    public static ValidationHandler standardChain(Clock clock, long maxAmountMinor) {
        return new ProcessingCodeHandler()
                .then(new LuhnHandler())
                .then(new AmountHandler(maxAmountMinor))
                .then(new ExpiryHandler(clock));
    }

    /** Only purchases (000000) are supported by this switch. */
    public static final class ProcessingCodeHandler extends ValidationHandler {
        @Override
        protected Optional<ResponseCode> check(AuthRequest r) {
            return "000000".equals(r.processingCode()) ? Optional.empty() : Optional.of(ResponseCode.INVALID_TRANSACTION);
        }
    }

    public static final class LuhnHandler extends ValidationHandler {
        @Override
        protected Optional<ResponseCode> check(AuthRequest r) {
            return PanUtil.luhnValid(r.pan()) ? Optional.empty() : Optional.of(ResponseCode.INVALID_CARD);
        }
    }

    public static final class AmountHandler extends ValidationHandler {
        private final long max;

        public AmountHandler(long maxAmountMinor) {
            this.max = maxAmountMinor;
        }

        @Override
        protected Optional<ResponseCode> check(AuthRequest r) {
            return r.amountMinor() > 0 && r.amountMinor() <= max ? Optional.empty() : Optional.of(ResponseCode.INVALID_AMOUNT);
        }
    }

    /** A card is valid through the last day of its expiry month. */
    public static final class ExpiryHandler extends ValidationHandler {
        private final Clock clock;

        public ExpiryHandler(Clock clock) {
            this.clock = clock;
        }

        @Override
        protected Optional<ResponseCode> check(AuthRequest r) {
            int yy = Integer.parseInt(r.expiryYYMM().substring(0, 2));
            int mm = Integer.parseInt(r.expiryYYMM().substring(2, 4));
            if (mm < 1 || mm > 12) return Optional.of(ResponseCode.FORMAT_ERROR);
            boolean expired = YearMonth.of(2000 + yy, mm).isBefore(YearMonth.now(clock));
            return expired ? Optional.of(ResponseCode.EXPIRED_CARD) : Optional.empty();
        }
    }
}
