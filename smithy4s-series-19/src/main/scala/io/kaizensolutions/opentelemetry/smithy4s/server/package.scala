package io.kaizensolutions.opentelemetry.smithy4s

import cats.data.Kleisli
import org.http4s.HttpApp
import smithy4s.http4s.ServerEndpointMiddleware
import smithy4s.Service
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

package object server {
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
                            _ => ZIO.succeed(span.recordException(mostImportantThrowable))
                          }
                        } else ZIO.unit
                    }
              }
          }
      }
    }
}
