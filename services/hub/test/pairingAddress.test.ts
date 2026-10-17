import { describe, expect, it } from "vitest";
import { resolvePublicAddress } from "../src/pairingAddress.js";

describe("resolvePublicAddress", () => {
  it("uses the detected LAN address when nothing is configured", () => {
    expect(resolvePublicAddress({}, 8443, () => "192.168.1.100")).toEqual({
      host: "192.168.1.100",
      port: 8443,
    });
  });

  it("prefers an explicit public host over detection", () => {
    const address = resolvePublicAddress(
      { HUB_PUBLIC_HOST: "192.168.1.100" },
      8443,
      () => "172.23.0.2",
    );
    expect(address.host).toBe("192.168.1.100");
  });

  it("prefers an explicit public port over the in-container port", () => {
    // The exact container case: HUB_PORT is 8443 inside, but the host publishes
    // 8444. A QR carrying 8443 sends the phone nowhere.
    const address = resolvePublicAddress(
      { HUB_PUBLIC_HOST: "192.168.1.100", HUB_PUBLIC_PORT: "8444" },
      8443,
      () => null,
    );
    expect(address).toEqual({ host: "192.168.1.100", port: 8444 });
  });

  it("trims whitespace around a configured host", () => {
    expect(resolvePublicAddress({ HUB_PUBLIC_HOST: "  hub.local  " }, 8443, () => null).host)
      .toBe("hub.local");
  });

  it("ignores an empty public host and falls back to detection", () => {
    expect(resolvePublicAddress({ HUB_PUBLIC_HOST: "" }, 8443, () => "192.168.1.5").host)
      .toBe("192.168.1.5");
  });

  it("rejects a public port that is not a valid port", () => {
    expect(() => resolvePublicAddress({ HUB_PUBLIC_PORT: "abc" }, 8443, () => "h")).toThrow(
      /HUB_PUBLIC_PORT/,
    );
    expect(() => resolvePublicAddress({ HUB_PUBLIC_PORT: "70000" }, 8443, () => "h")).toThrow(
      /HUB_PUBLIC_PORT/,
    );
  });

  it("fails loudly rather than printing an unreachable address", () => {
    // This is the container case with nothing configured. Printing a QR the
    // phone cannot use is worse than refusing: the failure would surface much
    // later, as a pairing that silently times out.
    expect(() => resolvePublicAddress({}, 8443, () => null)).toThrow(/HUB_PUBLIC_HOST/);
  });
});
