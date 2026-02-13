package infrastructure.util;

/**
 * Input validation utilities used at system boundaries (CLI argument parsing,
 * configuration building).
 *
 * <p>All methods throw {@link IllegalArgumentException} with a descriptive message
 * on invalid input so callers can propagate or display the reason to the user.
 * Internal invariants (algorithm logic) are protected by assertions, not these methods.
 */
public final class ValidationUtils {

    private ValidationUtils() {
        // Prevent instantiation — static methods only
    }

    /**
     * Validates that a probability value lies in the closed interval {@code [0, 1]}.
     *
     * @param probability the probability value to check
     * @param paramName   parameter name used in the error message
     * @throws IllegalArgumentException if {@code probability < 0 || probability > 1}
     */
    public static void validateProbability(double probability, String paramName) {
        if (probability < 0.0 || probability > 1.0) {
            throw new IllegalArgumentException(
                paramName + " must be in [0, 1], got: " + probability);
        }
    }

    /**
     * Validates that an integer value is strictly positive (greater than zero).
     *
     * @param value     the integer value to check
     * @param paramName parameter name used in the error message
     * @throws IllegalArgumentException if {@code value <= 0}
     */
    public static void validatePositive(int value, String paramName) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                paramName + " must be positive, got: " + value);
        }
    }
}
