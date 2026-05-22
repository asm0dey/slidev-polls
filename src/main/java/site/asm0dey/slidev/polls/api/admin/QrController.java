package site.asm0dey.slidev.polls.api.admin;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import site.asm0dey.slidev.polls.core.domain.Poll;
import site.asm0dey.slidev.polls.core.service.PollService;

/**
 * Produces a PNG QR code that encodes the poll's absolute public slug URL, for the presenter to
 * show on stage. Ownership is enforced the same way as {@link PollController}: the route is under
 * {@code /api/admin/**} (so anonymous traffic is rejected by the security layer) and {@link
 * PollService#getForOwner} refuses to return another presenter's poll.
 *
 * <p>@TS-026 — the decoded payload must exactly equal {@code {publicUrlBase}/{slug}} for the
 * presenter's audience to land on the voter SPA. 256×256 is the default size — large enough to be
 * scannable from a projector without blowing up the backoffice thumbnail.
 */
@Path("/api/admin/polls")
@ApplicationScoped
@RunOnVirtualThread
@RolesAllowed("ADMIN")
public class QrController {

  private static final int QR_SIZE_PX = 256;

  private final PollService pollService;

  public QrController(PollService pollService) {
    this.pollService = pollService;
  }

  @GET
  @Path("/{pollId}/qr.png")
  @Produces("image/png")
  public byte[] qr(
      @PathParam("pollId") UUID pollId,
      @Context SecurityIdentity identity,
      @Context RoutingContext request)
      throws IOException, WriterException {
    Poll poll = pollService.getForOwner(pollId, identity.getPrincipal().getName());
    String url = PublicUrlBase.of(request) + "/" + poll.slug();
    return renderPng(url, QR_SIZE_PX);
  }

  private static byte[] renderPng(String payload, int size) throws IOException, WriterException {
    Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
    hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
    hints.put(EncodeHintType.MARGIN, 1);
    BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints);
    try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
      return baos.toByteArray();
    }
  }
}
