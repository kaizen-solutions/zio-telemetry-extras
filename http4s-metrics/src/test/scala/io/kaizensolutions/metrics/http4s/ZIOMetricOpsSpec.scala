package io.kaizensolutions.metrics.http4s

import org.http4s.{Method, Status}
import org.http4s.metrics.TerminationType
import zio.*
import zio.metrics.*
import zio.metrics.MetricKeyType.Histogram
import zio.test.*

object ZIOMetricOpsSpec extends ZIOSpecDefault {

  private val boundaries =
    Histogram.Boundaries.fromChunk(Chunk(0.5, 1.0, 2.0))

  private val staticLabels = Set(MetricLabel("service", "checkout"))

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZIOMetricOps") {
      test("uses unprefixed metric names by default") {
        val ops = ZIOMetricOps.make[Any, Nothing](ZIOMetricOpsConfig.default)

        for {
          _     <- ops.increaseActiveRequests(Some("prefixed"))
          state <- gauge(
            "active_requests",
            Set(MetricLabel("classifier", "prefixed"))
          )
        } yield assertTrue(state.value == 1.0)
      } +
        test("tracks active requests by classifier") {
          val ops = make("active")

          for {
            _            <- ops.increaseActiveRequests(Some("api"))
            _            <- ops.increaseActiveRequests(Some("api"))
            _            <- ops.decreaseActiveRequests(Some("api"))
            _            <- ops.increaseActiveRequests(None)
            classified   <- gauge("active_active_requests", labels("classifier" -> "api"))
            unclassified <- gauge("active_active_requests", labels())
          } yield assertTrue(
            classified.value == 1.0,
            unclassified.value == 1.0
          )
        } +
        test("records header latency in seconds with request labels") {
          val ops = make("headers")

          for {
            _     <- ops.recordHeadersTime(Method.GET, 500_000_000L, Some("api"))
            state <- histogram(
              "headers_response_latency",
              labels(
                "classifier" -> "api",
                "method"     -> "GET",
                "phase"      -> "headers"
              )
            )
          } yield assertTrue(
            state.count == 1L,
            state.min == 0.5,
            state.max == 0.5,
            state.sum == 0.5
          )
        } +
        test("records request totals and every status bucket") {
          val ops   = make("total")
          val cases = Chunk(
            Status.Continue            -> "1xx",
            Status.Ok                  -> "2xx",
            Status.NotModified         -> "3xx",
            Status.BadRequest          -> "4xx",
            Status.InternalServerError -> "5xx"
          )

          for {
            _ <- ZIO.foreachDiscard(cases) { case (status, _) =>
              ops.recordTotalTime(Method.POST, status, 1_000_000_000L, Some("routes"))
            }
            states <- ZIO.foreach(cases) { case (status, bucket) =>
              for {
                request <- counter(
                  "total_request_count",
                  labels(
                    "classifier" -> "routes",
                    "method"     -> "POST",
                    "status"     -> status.code.toString
                  )
                )
                duration <- histogram(
                  "total_response_latency",
                  labels(
                    "classifier"    -> "routes",
                    "method"        -> "POST",
                    "status_bucket" -> bucket,
                    "phase"         -> "body"
                  )
                )
              } yield request -> duration
            }
          } yield assertTrue(states.forall { case (request, duration) =>
            request.count == 1.0 &&
            duration.count == 1L &&
            duration.min == 1.0 &&
            duration.max == 1.0 &&
            duration.sum == 1.0
          })
        } +
        test("records every abnormal termination type") {
          val ops      = make("abnormal")
          val abnormal = new RuntimeException("abnormal")
          val error    = new IllegalArgumentException("error")
          val cases: Chunk[(TerminationType, String, Option[String])] = Chunk(
            (
              TerminationType.Abnormal(abnormal),
              "abnormal",
              Some(classOf[RuntimeException].getName)
            ),
            (TerminationType.Canceled, "canceled", None),
            (
              TerminationType.Error(error),
              "error",
              Some(classOf[IllegalArgumentException].getName)
            ),
            (TerminationType.Timeout, "timeout", None)
          )

          for {
            _ <- ZIO.foreachDiscard(cases) { case (terminationType, _, _) =>
              ops.recordAbnormalTermination(250_000_000L, terminationType, Some("api"))
            }
            states <- ZIO.foreach(cases) { case (_, terminationType, cause) =>
              val metricLabels = labels(
                (Seq(
                  "classifier"       -> "api",
                  "termination_type" -> terminationType
                ) ++ cause.map("cause" -> _))*
              )

              for {
                count    <- counter("abnormal_abnormal_count", metricLabels)
                duration <- histogram("abnormal_abnormal_latency", metricLabels)
              } yield count -> duration
            }
          } yield assertTrue(states.forall { case (count, duration) =>
            count.count == 1.0 &&
            duration.count == 1L &&
            duration.min == 0.25 &&
            duration.max == 0.25 &&
            duration.sum == 0.25
          })
        }
    }

  private def make(prefix: String) =
    ZIOMetricOps.make[Any, Nothing](
      ZIOMetricOpsConfig(
        metricPrefix = Some(prefix),
        metricLabels = staticLabels,
        histogramBoundaries = boundaries
      )
    )

  private def labels(entries: (String, String)*): Set[MetricLabel] =
    staticLabels ++ entries.iterator.map { case (key, value) => MetricLabel(key, value) }.toSet

  private def gauge(name: String, metricLabels: Set[MetricLabel]): UIO[MetricState.Gauge] =
    Metric.gauge(name).tagged(metricLabels).value

  private def counter(name: String, metricLabels: Set[MetricLabel]): UIO[MetricState.Counter] =
    Metric.counter(name).tagged(metricLabels).value

  private def histogram(name: String, metricLabels: Set[MetricLabel]): UIO[MetricState.Histogram] =
    Metric.histogram(name, boundaries).tagged(metricLabels).value
}
