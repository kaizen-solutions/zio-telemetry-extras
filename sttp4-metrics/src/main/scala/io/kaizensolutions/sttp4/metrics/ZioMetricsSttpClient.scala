package io.kaizensolutions.sttp4.metrics

import sttp.capabilities.{Effect, WebSockets}
import sttp.capabilities.zio.ZioStreams
import sttp.client4.{GenericRequest, Response}
import sttp.client4.httpclient.zio.SttpClient
import sttp.client4.wrappers.DelegateBackend
import sttp.model.StatusCode
import zio.*
import zio.metrics.{Metric, MetricLabel}
import zio.metrics.MetricKeyType.Histogram.Boundaries

final class ZioMetricsSttpClient(
    delegate: SttpClient,
    urlNamer: GenericRequest[?, ?] => String = _.uri.host.getOrElse("N/A")
) extends DelegateBackend[Task, ZioStreams & WebSockets](delegate)
    with SttpClient {
  private val nanosToSeconds = 1e9d

  private val requestLatency = {
    // 0.005s to 30s
    val durationBoundaries: Boundaries =
      Boundaries.fromChunk(
        Chunk(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10, 15, 30)
      )

    Metric.histogram("sttp_request_latency", durationBoundaries)
  }

  private val activeRequests =
    Metric.gauge("sttp_active_requests")

  private val requestTotal =
    Metric.counter("sttp_request_total")

  override def send[T](
      request: GenericRequest[T, ZioStreams & WebSockets & Effect[Task]]
  ): Task[Response[T]] = {
    val reqTags = requestTags(request)
    for {
      _        <- activeRequests.tagged(reqTags).increment
      _        <- requestTotal.tagged(reqTags).increment
      start    <- Clock.nanoTime
      response <- delegate.send(request).tapError(failurePath(start, reqTags))
      result   <- successPath(start, reqTags, response)
    } yield result
  }

  private def successPath[Out](
      startTimeNs: Long,
      reqTags: Set[MetricLabel],
      response: Response[Out]
  ) = {
    val respTags = responseTags(response)
    for {
      finish <- Clock.nanoTime
      totalNs = finish - startTimeNs
      _ <- requestLatency.tagged(reqTags ++ respTags).update(totalNs / nanosToSeconds)
      _ <- activeRequests.tagged(reqTags).decrement
    } yield response
  }

  private def failurePath(startTimeNs: Long, reqTags: Set[MetricLabel])(
      error: Throwable
  ): UIO[Unit] = {
    val _        = error
    val respTags = Set(MetricLabel("response_status", "failure"))
    for {
      finish <- Clock.nanoTime
      totalNs = finish - startTimeNs
      _ <- requestLatency.tagged(reqTags ++ respTags).update(totalNs / nanosToSeconds)
      _ <- activeRequests.tagged(reqTags).decrement
    } yield ()
  }

  private def requestTags(request: GenericRequest[?, ?]): Set[MetricLabel] =
    Set(
      MetricLabel("request_method", request.method.method),
      MetricLabel("request_url", urlNamer(request))
    )

  private def responseTags(response: Response[?]): Set[MetricLabel] =
    Set(MetricLabel("response_status", renderStatus(response.code)))

  private def renderStatus(code: StatusCode): String =
    code match {
      case s if s.isInformational => "1xx"
      case s if s.isSuccess       => "2xx"
      case s if s.isRedirect      => "3xx"
      case s if s.isClientError   => "4xx"
      case s if s.isServerError   => "5xx"
      case _                      => "unknown"
    }
}
