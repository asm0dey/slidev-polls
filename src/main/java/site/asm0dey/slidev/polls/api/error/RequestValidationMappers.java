package site.asm0dey.slidev.polls.api.error;

import static site.asm0dey.slidev.polls.api.error.DomainExceptionMappers.respond;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import io.quarkus.hibernate.validator.runtime.jaxrs.ResteasyReactiveViolationException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps request-shape failures — bean-validation constraint violations and a body Jackson cannot
 * bind — to a {@code 400 VALIDATION_FAILED} {@link Problem}. This mirrors the Spring {@code
 * MethodArgumentNotValidException} / {@code HttpMessageNotReadableException} handling that lived in
 * the old {@code GlobalExceptionHandler}, keeping the per-field {@code errors} map shape so the SPA
 * can still highlight the offending field.
 */
public class RequestValidationMappers {

  /**
   * Bean-validation failures on a resource method (constrained DTO body or parameters). Quarkus
   * surfaces these as {@link ResteasyReactiveViolationException} (a subtype of {@code
   * jakarta.validation.ConstraintViolationException}). Each violation's leaf property name is keyed
   * to the list of its messages, mirroring the old {@code FieldError::getField} grouping.
   */
  @ServerExceptionMapper
  public RestResponse<Problem> mapValidation(ResteasyReactiveViolationException ex) {
    Map<String, List<String>> fieldErrors =
        ex.getConstraintViolations().stream()
            .collect(
                Collectors.groupingBy(
                    RequestValidationMappers::leafProperty,
                    Collectors.mapping(
                        v -> {
                          String m = v.getMessage();
                          return (m == null || m.isBlank()) ? "invalid value" : m;
                        },
                        Collectors.toList())));
    return respond(
        Response.Status.BAD_REQUEST,
        ProblemCode.VALIDATION_FAILED,
        "request body is invalid",
        fieldErrors);
  }

  /**
   * A request body that parsed but did not match the target type (wrong JSON types, an array where
   * an object was expected, etc.). The stock {@code quarkus-rest-jackson} reader rethrows {@link
   * MismatchedInputException} un-wrapped specifically so a mapper can own it; resteasy-reactive's
   * superclass resolution lets this single mapper cover the whole {@code MismatchedInputException}
   * hierarchy. We emit a neutral message rather than Jackson's internal wording.
   */
  @ServerExceptionMapper
  public RestResponse<Problem> mapUnreadableBody(MismatchedInputException ex) {
    return respond(
        Response.Status.BAD_REQUEST, ProblemCode.VALIDATION_FAILED, "request body is invalid");
  }

  /**
   * Leaf segment of a constraint-violation property path, e.g. {@code create.body.title -> title}.
   */
  private static String leafProperty(ConstraintViolation<?> violation) {
    String leaf = null;
    for (Path.Node node : violation.getPropertyPath()) {
      if (node.getName() != null) {
        leaf = node.getName();
      }
    }
    return leaf == null ? "" : leaf;
  }
}
