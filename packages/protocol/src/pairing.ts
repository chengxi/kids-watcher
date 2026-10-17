/** Digits in a pairing code. Short enough to type; see the design doc on why that is safe. */
export const PAIRING_CODE_DIGITS = 6;

/** Bytes of the certificate fingerprint a person is asked to compare. */
export const FINGERPRINT_CHECK_BYTES = 3;

/**
 * The first bytes of a SHA-256 certificate fingerprint, in the same uppercase
 * colon form the pair command and `openssl x509 -fingerprint -sha256` print.
 * This is a view of the fingerprint, never a second encoding of it: whoever
 * compares it can also check it against the full value.
 */
export function fingerprintCheck(fingerprintSha256: string): string {
  const hex = fingerprintSha256.trim().replace(/[\s:]/g, "").toUpperCase();
  if (!/^[0-9A-F]{64}$/.test(hex)) {
    throw new Error(`not a SHA-256 fingerprint: '${fingerprintSha256.trim()}'`);
  }
  return (hex.match(/.{2}/g) ?? []).slice(0, FINGERPRINT_CHECK_BYTES).join(":");
}
