package io.kaizensolutions.opentelemetry.smithy4s

import cats.data.Kleisli
import io.kaizensolutions.opentelemetry.http4s
import org.http4s.HttpApp
import smithy4s.http4s.ServerEndpointMiddleware
import smithy4s.Service
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

package object server {

  /** Smithy4s middleware that adds smithy attributes to an existing (already opened) span. Requires
    * use of the http4s middleware to create the enclosing span
    *
    * @param trace
    * @return
    */
  def middleware[R, E](trace: Tracing): ServerEndpointMiddleware[ZIO[R, E, *]] =
    new ServerEndpointMiddleware[ZIO[R, E, *]] {

      override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
          endpoint: service.Endpoint[?, ?, ?, ?, ?]
      ): HttpApp[ZIO[R, E, *]] => HttpApp[ZIO[R, E, *]] = {
        val smithyAttributes = attributes(service)(endpoint)

        httpApp =>
          Kleisli { req =>
            trace.getCurrentSpanUnsafe
              .flatMap { span =>
                ZIO.succeed(span.setAllAttributes(smithyAttributes)) *>
                  httpApp(req)
                    .onExit {
                      case Exit.Success(_) =>
                        ZIO.unit

                      case Exit.Failure(cause) =>
                        if (!cause.isDie && !cause.isInterrupted) {
                          val mostImportantThrowable = cause.squashWith {
                            case e: Throwable => e
                            case o            => new RuntimeException(o.toString())
                          }
                          // Known Smithy errors only
                          ZIO.foreach(endpoint.error.flatMap(_.liftError(mostImportantThrowable))) {
                            _ =>
                              ZIO.succeed(
                                span.recordException(
                                  mostImportantThrowable,
                                  smithyAttributes
                                )
                              )
                          }
                        } else ZIO.unit
                    }
              }
          }
      }
    }

  /** Smithy4s middleware that creates a span and adds request + smithy4s attributes. Does not
    * require the use of any other middleware. This slightly differs from the middleware when it
    * comes to tagging expected Smithy exceptions.
    *
    * NOTE: Make sure you use `.encodeErrorsBeforeMiddleware(true)` in conjunction with
    * `.middleware(standaloneMiddleware(...))` so the correct status codes will be reported
    *
    * @param trace
    * @param config
    * @return
    */
  def standaloneMiddleware[R, E](
      trace: Tracing,
      config: http4s.TracingConfig = http4s.TracingConfig.default
  ) =
    new ServerEndpointMiddleware[ZIO[R, E, *]] {

      override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
          endpoint: service.Endpoint[?, ?, ?, ?, ?]
      ): HttpApp[ZIO[R, E, *]] => HttpApp[ZIO[R, E, *]] =
        http4s.server.middlewareWithContext[R, E](trace, config)(request =>
          http4s.server
            .requestAttributes(request)
            .toBuilder()
            .putAll(attributes(service)(endpoint))
            .build()
        )
    }
}
