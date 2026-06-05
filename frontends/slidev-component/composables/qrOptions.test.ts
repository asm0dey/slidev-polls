import { describe, it, expect } from "vitest";
import { buildQrOptions } from "./qrOptions";

describe("buildQrOptions", () => {
  it("encodes the given url as an svg with rounded dots", () => {
    const opts = buildQrOptions("https://example.test/my-poll");
    expect(opts.type).toBe("svg");
    expect(opts.data).toBe("https://example.test/my-poll");
    expect(opts.dotsOptions?.type).toBe("rounded");
    expect(opts.cornersSquareOptions?.type).toBe("extra-rounded");
    expect(opts.cornersDotOptions?.type).toBe("dot");
    expect(opts.qrOptions?.errorCorrectionLevel).toBe("M");
    expect(opts.width).toBe(512);
    expect(opts.height).toBe(512);
    expect(opts.margin).toBe(8);
    expect(opts.backgroundOptions?.color).toBe("#ffffff");
    expect(opts.dotsOptions?.color).toBe("#111111");
  });
});
