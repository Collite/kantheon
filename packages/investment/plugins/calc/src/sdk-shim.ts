// FO-P4 — local shim of @tatrman/package-sdk's canon-function SPI (§3). The real SDK lives in tatrman
// (Apache, published); RO-6 forbids a cross-repo local link until the ⚑2 publish cut. A package plugin
// depends on the SDK shape ALONE — the cert lever (FO-23).
//
// FO-P4: adopt `@tatrman/package-sdk` at the ⚑2 publish cut — RO-6.

export interface TypedSignature {
  params: { name: string; type: string }[];
  returns: string;
}

/**
 * §3 canon-function: a pure, versioned function callable from TTR-P (the escape hatch for calc beyond
 * the language). No I/O, no clock — determinism is the contract; the version pins it in the entry record.
 */
export interface CanonFunction<A extends readonly unknown[] = readonly unknown[], R = unknown> {
  readonly id: string;
  readonly version: string;
  readonly signature: TypedSignature;
  eval(...args: A): R;
}
