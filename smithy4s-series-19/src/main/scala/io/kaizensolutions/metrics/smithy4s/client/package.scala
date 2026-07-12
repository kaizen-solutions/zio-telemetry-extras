package io.kaizensolutions.metrics.smithy4s

import io.kaizensolutions.metrics.http4s.{ZIOMetricOps, ZIOMetricOpsConfig}
import org.http4s.client.middleware.Metrics
import org.http4s.client.Client
import org.http4s.Request
import smithy4s.http4s.ClientEndpointMiddleware
import smithy4s.Service
import zio.*
import zio.interop.catz.*

package object client {

  /** Standalone metrics client endpoint middleware that tags all metrics reported with additional
    * smithy4s information
    *
    * @param config
    * @param requestClassifier
    * @return
    */
  def middleware[R](
      config: ZIOMetricOpsConfig = ZIOMetricOpsConfig.default,
      requestClassifier: Request[RIO[R, *]] => Option[String] = (_: Request[RIO[R, *]]) => None
  ): ClientEndpointMiddleware[RIO[R, *]] =
    new ClientEndpointMiddleware[RIO[R, *]] {

      override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
          endpoint: service.Endpoint[?, ?, ?, ?, ?]
      ): Client[RIO[R, *]] => Client[RIO[R, *]] = {
        val smithyConfig = config.addLabels(metricLabels(service)(endpoint))
        val ops          = ZIOMetricOps.make[R, Throwable](smithyConfig)
        Metrics[RIO[R, *]](ops = ops, classifierF = requestClassifier)
      }
    }
}
