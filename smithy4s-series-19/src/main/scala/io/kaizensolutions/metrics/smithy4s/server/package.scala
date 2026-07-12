package io.kaizensolutions.metrics.smithy4s

import cats.data.OptionT
import io.kaizensolutions.metrics.http4s.*
import org.http4s.{HttpApp, HttpRoutes, Request}
import org.http4s.metrics.MetricsOps
import org.http4s.server.middleware.Metrics
import smithy4s.http4s.ServerEndpointMiddleware
import smithy4s.Service
import zio.*
import zio.interop.catz.*

package object server {

  /** Standalone metrics server endpoint middleware that tags all metrics reported with additional
    * smithy4s information
    *
    * NOTE: Make sure you use `.encodeErrorsBeforeMiddleware(true)` in conjunction with
    * `.middleware(metrics.smithy4s.server.middleware(...))` so the correct status codes will be
    * reported. However, if you choose not to do this, you get access to the cause. label which will
    * report exception names
    *
    * @param config
    * @param requestClassifier
    * @return
    */
  def middleware[R](
      config: ZIOMetricOpsConfig = ZIOMetricOpsConfig.default,
      requestClassifier: Request[RIO[R, *]] => Option[String] = (_: Request[RIO[R, *]]) => None
  ): ServerEndpointMiddleware[RIO[R, *]] =
    new ServerEndpointMiddleware[RIO[R, *]] {
      override def prepare[Alg[_[_, _, _, _, _]]](service: Service[Alg])(
          endpoint: service.Endpoint[?, ?, ?, ?, ?]
      ): HttpApp[RIO[R, *]] => HttpApp[RIO[R, *]] = {
        val smithyConfig               = config.addLabels(metricLabels(service)(endpoint))
        val ops: MetricsOps[RIO[R, *]] = ZIOMetricOps.make[R, Throwable](smithyConfig)

        app => {
          val httpRoutes: HttpRoutes[RIO[R, *]] = app.mapK(OptionT.liftK[RIO[R, *]])
          Metrics[RIO[R, *]](ops = ops, classifierF = requestClassifier)(httpRoutes).orNotFound
        }
      }
    }
}
