package io.kaizensolutions.opentelemetry.http4s

import cats.effect.Resource
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import org.http4s.{Request, Response}
import org.http4s.client.Client
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.context.OutgoingContextCarrier
import zio.telemetry.opentelemetry.tracing.Tracing

package object client {
  def middleware[R](trace: Tracing, config: TracingConfig = TracingConfig.default)(
      client: Client[RIO[R, *]]
  ): Client[RIO[R, *]] = middlewareWithContext(trace, config)(client) { req =>
    Attributes
      .builder()
      .put("client.http.url", req.uri.renderString)
      .put("client.http.method", req.method.toString())
      .put("client.http.version", req.httpVersion.renderString)
      .build()
  }

  def middlewareWithContext[R](trace: Tracing, config: TracingConfig)(
      client: Client[RIO[R, *]]
  )(context: Request[RIO[R, *]] => Attributes): Client[RIO[R, *]] =
    Client { req =>
      Resource.applyFull { cancellable =>
        trace.span("client.http.request", SpanKind.CLIENT)(
          cancellable(base(trace, config)(client)(context).run(req).allocatedCase)
        )
      }
    }

  def base[R](trace: Tracing, config: TracingConfig)(
      client: Client[RIO[R, *]]
  )(context: Request[RIO[R, *]] => Attributes): Client[RIO[R, *]] = Client { request =>
    Resource.applyFull[RIO[R, *], Response[RIO[R, *]]] { cancellable =>
      val outgoingCarrier = OutgoingContextCarrier.default()
      val attributes      = context(request)
      for {
        span <- trace.getCurrentSpanUnsafe
        _ = span.setAllAttributes(attributes)
        _ <- trace.injectSpan(config.propagator, outgoingCarrier)
        traceHeaders            = outgoingHeaders(outgoingCarrier.kernel)
        requestWithTraceHeaders = request.withHeaders(traceHeaders ++ request.headers)
        response <- cancellable(client.run(requestWithTraceHeaders).allocatedCase)
        _        <- trace.setAttribute("client.http.status_code", response._1.status.code.toLong)
      } yield response
    }
  }
}
