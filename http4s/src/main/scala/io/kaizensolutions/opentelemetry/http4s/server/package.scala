package io.kaizensolutions.opentelemetry.http4s

import cats.data.Kleisli
import io.kaizensolutions.opentelemetry.http4s.*
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import org.http4s.*
import zio.*
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing

package object server {

  /** Middleware that creates a span for every http request
    *
    * Note: This is intentionally implemented using `HttpApp` as opposed to `HttpRoutes` to avoid
    * creating unnecessary spans when routes don't match
    *
    * @param trace
    * @param config
    * @param http
    * @return
    */
  def middleware[R, E](
      trace: Tracing,
      config: TracingConfig = TracingConfig.default
  )(http: HttpApp[ZIO[R, E, *]]): HttpApp[ZIO[R, E, *]] =
    Kleisli { request =>
      val carrier = incomingHeadersCarrier(request.headers)

      val requestAttributes = Attributes
        .builder()
        .put("http.request.method", request.method.name)
        .put("http.request.url", request.uri.renderString)
        .put("http.request.version", request.httpVersion.renderString)
        .build()

      trace
        .extractSpan(
          propagator = config.propagator,
          carrier = carrier,
          spanName = "http.request",
          spanKind = SpanKind.SERVER,
          attributes = requestAttributes
        ) {
          val outgoingCarrier = OutgoingContextCarrier.default()
          val response        =
            for {
              response <- http.run(request)
              _        <- trace.injectSpan(config.propagator, outgoingCarrier)
              kernel                   = outgoingCarrier.kernel
              responseWithTraceHeaders = response.withHeaders(
                outgoingHeaders(kernel) ++ response.headers
              )
            } yield responseWithTraceHeaders

          response
            .onExit {
              case Exit.Success(resp) =>
                val serverError =
                  Option.when(!resp.status.isSuccess)(
                    resp.status.responseClass == Status.ServerError
                  )
                ZIO.foreach(serverError)(err => trace.setAttribute("errorType", err)) *>
                  trace.setAttribute("http.response.status_code", resp.status.code.toLong)

              case Exit.Failure(cause) =>
                trace.getCurrentSpanUnsafe
                  .flatMap { span =>
                    if (cause.isDie) trace.setAttribute("cause", cause.prettyPrint)
                    else if (cause.isInterrupted) trace.setAttribute("interrupted", true)
                    else {
                      val throwableCause = cause.map {
                        case e: Throwable => e
                        case other        => new RuntimeException(other.toString())
                      }
                      val mostImportant = throwableCause.squash
                      ZIO.succeed(span.recordException(mostImportant))
                    }
                  }
            }
        }
    }
}
