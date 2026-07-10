package io.kaizensolutions.opentelemetry.smithy4s

import io.kaizensolutions.opentelemetry.http4s
import io.kaizensolutions.opentelemetry.http4s.*
import org.http4s.client.Client
import smithy4s.http4s.ClientEndpointMiddleware
import smithy4s.Service
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing

package object client {

  /** Smithy4s Client endpoint middleware that creates an http4s client span and adds smithy4s
    * attributes to it. You do not need to use the http4s middleware on the underlying client
    *
    * @param trace
    * @param config
    * @return
    */
  def middleware[R](
      trace: Tracing,
      config: TracingConfig = TracingConfig.default
  ): ClientEndpointMiddleware[RIO[R, *]] = new ClientEndpointMiddleware[RIO[R, *]] {
    override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
        endpoint: service.Endpoint[?, ?, ?, ?, ?]
    ): Client[RIO[R, *]] => Client[RIO[R, *]] = {
      val smithyAttributes = attributes(service)(endpoint)

      client =>
        http4s.client.middlewareWithContext[R](trace, config)(client) { request =>
          http4s.client
            .requestAttributes(request)
            .toBuilder()
            .putAll(smithyAttributes)
            .build()
        }
    }
  }
}
