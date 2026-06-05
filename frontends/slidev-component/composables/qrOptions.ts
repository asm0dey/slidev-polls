import type { Options } from "qr-code-styling";

// Single source of truth for the styled-QR look shared by PollQr (inline) and
// PollQrButton (overlay). Rounded dots + extra-rounded finder squares + dot
// finder cores on a white background, rendered as an SVG at 512px.
export function buildQrOptions(url: string): Options {
  return {
    width: 512,
    height: 512,
    type: "svg",
    data: url,
    margin: 8,
    qrOptions: { errorCorrectionLevel: "M" },
    dotsOptions: { type: "rounded", color: "#111111" },
    cornersSquareOptions: { type: "extra-rounded", color: "#111111" },
    cornersDotOptions: { type: "dot", color: "#111111" },
    backgroundOptions: { color: "#ffffff" }
  };
}
