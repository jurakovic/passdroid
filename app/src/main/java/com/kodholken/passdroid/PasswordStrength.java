package com.kodholken.passdroid;

/**
 * The strength of a generated password, made of random characters: its bits
 * of randomness and a rating of them. About 80 bits can not be found by
 * trying, even when a service stores the password poorly.
 */
public class PasswordStrength {
    public static final int VERY_WEAK = 0;
    public static final int WEAK = 1;
    public static final int FAIR = 2;
    public static final int GOOD = 3;
    public static final int EXCELLENT = 4;

    // The bits of an excellent password, e.g. where a meter is full
    public static final int EXCELLENT_BITS = 100;

    // The bits a password needs for WEAK, FAIR, GOOD and EXCELLENT
    private static final int [] LIMITS = { 40, 65, 75, EXCELLENT_BITS };

    /**
     * @return the bits of randomness of a password with length characters,
     *         each chosen at random from charsetSize characters, rounded
     *         down.
     */
    public static int getBits(int length, int charsetSize) {
        if (length <= 0 || charsetSize <= 1) {
            return 0;
        }

        return (int) Math.floor(length * Math.log(charsetSize) / Math.log(2));
    }

    /**
     * @return VERY_WEAK to EXCELLENT.
     */
    public static int getRating(int bits) {
        int rating = VERY_WEAK;
        while (rating < LIMITS.length && bits >= LIMITS[rating]) {
            rating++;
        }

        return rating;
    }
}
