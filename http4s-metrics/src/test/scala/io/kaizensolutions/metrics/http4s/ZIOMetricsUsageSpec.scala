package io.kaizensolutions.metrics.http4s

import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.metrics.MetricsOps
import org.http4s.server.middleware.Metrics as ServerMetrics
import org.http4s.syntax.all.*
import zio.*
import zio.interop.catz.*
import zio.metrics.{Metric, MetricLabel}
import zio.test.*

object ZIOMetricsUsageSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  val thirtyms = 30.milliseconds

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZIO Metrics for http4s servers")(
      test("records metrics for successful requests") {
        val config = ZIOMetricOpsConfig.default
          .addLabels(Set(MetricLabel("kind", "server")))
        val metricOps: MetricsOps[Task] =
          ZIOMetricOps.make(config)
        val trackedRoutes = ServerMetrics[Task](metricOps)(routes).orNotFound

        val activeRequests =
          Metric.gauge("active_requests").tagged(config.metricLabels)

        val requestCount = Metric
          .counter("request_count")
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "GET"),
              MetricLabel("status", Status.Ok.code.toString)
            )
          )
        val headersLatency = Metric
          .histogram("response_latency", config.histogramBoundaries)
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "GET"),
              MetricLabel("phase", "headers")
            )
          )
        val bodyLatency = Metric
          .histogram("response_latency", config.histogramBoundaries)
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "GET"),
              MetricLabel("status_bucket", "2xx"),
              MetricLabel("phase", "body")
            )
          )
        val expectedLatency = thirtyms.toNanos.toDouble / 1e9

        for {
          fiber <- trackedRoutes.run(Request(method = Method.GET, uri = uri"/hello/42")).fork
          _     <- TestClock.adjust(20.milliseconds)
          activeWhileStreaming <- activeRequests.value
          _                    <- TestClock.adjust(10.milliseconds)
          res                  <- fiber.join
          headers              <- headersLatency.value
          body                 <- res.bodyText.compile.string
          activeAfterStreaming <- activeRequests.value
          requests             <- requestCount.value
          total                <- bodyLatency.value
        } yield assertTrue(
          res.status == Status.Ok,
          body == "Hello, 42!",
          activeWhileStreaming.value == 1.0,
          activeAfterStreaming.value == 0.0,
          requests.count == 1.0,
          headers.count == 1L,
          headers.min == expectedLatency,
          headers.max == expectedLatency,
          headers.sum == expectedLatency,
          total.count == 1L,
          total.min == expectedLatency,
          total.max == expectedLatency,
          total.sum == expectedLatency
        )
      },
      test("records metrics when a route fails before producing a response") {
        val config = ZIOMetricOpsConfig.default
          .addLabels(
            Set(
              MetricLabel("kind", "server"),
              MetricLabel("scenario", "abnormal")
            )
          )
        val metricOps: MetricsOps[Task] =
          ZIOMetricOps.make(config)
        val trackedRoutes = ServerMetrics[Task](metricOps)(routes).orNotFound

        val activeRequests =
          Metric.gauge("active_requests").tagged(config.metricLabels)
        val requestCount = Metric
          .counter("request_count")
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "POST"),
              MetricLabel("status", Status.InternalServerError.code.toString)
            )
          )
        val headersLatency = Metric
          .histogram("response_latency", config.histogramBoundaries)
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "POST"),
              MetricLabel("phase", "headers")
            )
          )
        val bodyLatency = Metric
          .histogram("response_latency", config.histogramBoundaries)
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "POST"),
              MetricLabel("status_bucket", "5xx"),
              MetricLabel("phase", "body")
            )
          )
        val abnormalLabels = config.metricLabels ++ Set(
          MetricLabel("termination_type", "error"),
          MetricLabel("cause", classOf[RuntimeException].getName)
        )
        val abnormalCount =
          Metric.counter("abnormal_count").tagged(abnormalLabels)
        val abnormalLatency =
          Metric
            .histogram("abnormal_latency", config.histogramBoundaries)
            .tagged(abnormalLabels)

        for {
          result <- trackedRoutes
            .run(Request(method = Method.POST, uri = uri"/fail"))
            .either
          active   <- activeRequests.value
          requests <- requestCount.value
          headers  <- headersLatency.value
          total    <- bodyLatency.value
          abnormal <- abnormalCount.value
          duration <- abnormalLatency.value
        } yield assertTrue(
          result.is(_.left).isInstanceOf[RuntimeException],
          result.is(_.left).getMessage() == "Boom!",
          active.value == 0.0,
          requests.count == 1.0,
          abnormal.count == 1.0,
          // no response means no headers were sent, so http4s skips recordHeadersTime
          headers.count == 0L,
          headers.sum == 0.0,
          total.count == 1L,
          total.min == 0.0,
          total.max == 0.0,
          total.sum == 0.0,
          duration.count == 1L,
          duration.min == 0.0,
          duration.max == 0.0,
          duration.sum == 0.0
        )
      },
      test("records metrics when a request is canceled before producing a response") {
        val config = ZIOMetricOpsConfig.default
          .addLabels(
            Set(
              MetricLabel("kind", "server"),
              MetricLabel("scenario", "canceled")
            )
          )
        val metricOps: MetricsOps[Task] =
          ZIOMetricOps.make(config)
        val trackedRoutes = ServerMetrics[Task](metricOps)(routes).orNotFound

        val activeRequests =
          Metric.gauge("active_requests").tagged(config.metricLabels)
        val requestCount = Metric
          .counter("request_count")
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "GET"),
              MetricLabel("status", ServerMetrics.CanceledStatus.code.toString)
            )
          )
        val headersLatency = Metric
          .histogram("response_latency", config.histogramBoundaries)
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "GET"),
              MetricLabel("phase", "headers")
            )
          )
        val bodyLatency = Metric
          .histogram("response_latency", config.histogramBoundaries)
          .tagged(
            config.metricLabels ++ Set(
              MetricLabel("method", "GET"),
              MetricLabel("status_bucket", "4xx"),
              MetricLabel("phase", "body")
            )
          )
        val abnormalLabels = config.metricLabels ++ Set(
          MetricLabel("termination_type", "canceled")
        )
        val abnormalCount =
          Metric.counter("abnormal_count").tagged(abnormalLabels)
        val abnormalLatency =
          Metric
            .histogram("abnormal_latency", config.histogramBoundaries)
            .tagged(abnormalLabels)
        val expectedLatency = 20.milliseconds.toNanos.toDouble / 1e9

        for {
          fiber    <- trackedRoutes.run(Request(method = Method.GET, uri = uri"/slow")).fork
          _        <- TestClock.adjust(20.milliseconds)
          _        <- fiber.interrupt
          active   <- activeRequests.value
          requests <- requestCount.value
          headers  <- headersLatency.value
          total    <- bodyLatency.value
          abnormal <- abnormalCount.value
          duration <- abnormalLatency.value
        } yield assertTrue(
          active.value == 0.0,
          // http4s records canceled requests with a 499 status when no response was sent
          requests.count == 1.0,
          abnormal.count == 1.0,
          headers.count == 0L,
          total.count == 1L,
          total.sum == expectedLatency,
          duration.count == 1L,
          duration.sum == expectedLatency
        )
      }
    )

  val routes: HttpRoutes[Task] = {
    object dsl extends Http4sDsl[Task]
    import dsl.*

    HttpRoutes
      .of[Task] {
        case GET -> Root / "hello" / id =>
          ZIO.sleep(thirtyms) *> Ok(s"Hello, $id!")

        case GET -> Root / "slow" =>
          ZIO.sleep(1.hour) *> Ok("Too late")

        case POST -> Root / "fail" =>
          ZIO.fail(new RuntimeException("Boom!"))

        case DELETE -> Root / "boom" =>
          ZIO.die(new IllegalArgumentException("Bang!")) *> NotImplemented()
      }
  }

}
