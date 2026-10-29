package io.kaizensolutions.sttp4.metrics

import sttp.client4.*
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.client4.testing.ResponseStub
import sttp.model.StatusCode
import zio.*
import zio.metrics.*
import zio.metrics.MetricKeyType.Histogram
import zio.test.*

object ZioMetricsSttpClientSpec extends ZIOSpecDefault {

  // Must match the boundaries used by ZioMetricsSttpClient so that we read the same metric key
  private val boundaries =
    Histogram.Boundaries.fromChunk(
      Chunk(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1, 2.5, 5, 7.5, 10, 15, 30)
    )

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("ZioMetricsSttpClient") {
      test("records request total, latency and active requests for a successful request") {
        val host    = "success.example.com"
        val client  = new ZioMetricsSttpClient(HttpClientZioBackend.stub.whenAnyRequest.thenRespondAdjust("ok"))
        val reqTags = requestLabels("GET", host)

        for {
          response <- basicRequest.get(uri"http://$host/things").send(client)
          total    <- counter(reqTags)
          active   <- gauge(reqTags)
          latency  <- histogram(reqTags + status("2xx"))
        } yield assertTrue(
          response.code == StatusCode.Ok,
          response.body == Right("ok"),
          total.count == 1.0,
          active.value == 0.0,
          latency.count == 1L
        )
      } +
        test("records latency in seconds") {
          val host   = "latency.example.com"
          val client = new ZioMetricsSttpClient(
            HttpClientZioBackend.stub.whenAnyRequest
              .thenRespondF(ZIO.sleep(500.millis).as(ResponseStub.adjust("slow")))
          )

          for {
            fiber   <- basicRequest.get(uri"http://$host").send(client).fork
            _       <- TestClock.adjust(500.millis)
            _       <- fiber.join
            latency <- histogram(requestLabels("GET", host) + status("2xx"))
          } yield assertTrue(
            latency.count == 1L,
            latency.min == 0.5,
            latency.max == 0.5,
            latency.sum == 0.5
          )
        } +
        test("tracks requests that are in flight") {
          val host = "active.example.com"

          for {
            started <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            client = new ZioMetricsSttpClient(
              HttpClientZioBackend.stub.whenAnyRequest
                .thenRespondF(started.succeed(()) *> release.await.as(ResponseStub.adjust("done")))
            )
            fiber    <- basicRequest.post(uri"http://$host").send(client).fork
            _        <- started.await
            inFlight <- gauge(requestLabels("POST", host))
            _        <- release.succeed(())
            _        <- fiber.join
            finished <- gauge(requestLabels("POST", host))
          } yield assertTrue(
            inFlight.value == 1.0,
            finished.value == 0.0
          )
        } +
        test("buckets every response status class") {
          val host  = "status.example.com"
          val cases = Chunk(
            StatusCode.Continue            -> "1xx",
            StatusCode.Ok                  -> "2xx",
            StatusCode.NotModified         -> "3xx",
            StatusCode.BadRequest          -> "4xx",
            StatusCode.InternalServerError -> "5xx"
          )
          val client = new ZioMetricsSttpClient(
            HttpClientZioBackend.stub
              .whenRequestMatches(_.uri.path == List("1xx"))
              .thenRespondWithCode(StatusCode.Continue)
              .whenRequestMatches(_.uri.path == List("2xx"))
              .thenRespondWithCode(StatusCode.Ok)
              .whenRequestMatches(_.uri.path == List("3xx"))
              .thenRespondWithCode(StatusCode.NotModified)
              .whenRequestMatches(_.uri.path == List("4xx"))
              .thenRespondWithCode(StatusCode.BadRequest)
              .whenRequestMatches(_.uri.path == List("5xx"))
              .thenRespondWithCode(StatusCode.InternalServerError)
          )
          val reqTags = requestLabels("PUT", host)

          for {
            codes <- ZIO.foreach(cases) { case (_, bucket) =>
              basicRequest.put(uri"http://$host/$bucket").send(client).map(_.code)
            }
            latencies <- ZIO.foreach(cases) { case (_, bucket) => histogram(reqTags + status(bucket)) }
            total     <- counter(reqTags)
            active    <- gauge(reqTags)
          } yield assertTrue(
            codes == cases.map(_._1),
            latencies.forall(_.count == 1L),
            total.count == cases.size.toDouble,
            active.value == 0.0
          )
        } +
        test("records failed requests and propagates the error") {
          val host    = "failure.example.com"
          val boom    = new RuntimeException("boom")
          val client  = new ZioMetricsSttpClient(HttpClientZioBackend.stub.whenAnyRequest.thenThrow(boom))
          val reqTags = requestLabels("DELETE", host)

          for {
            exit    <- basicRequest.delete(uri"http://$host").send(client).exit
            total   <- counter(reqTags)
            active  <- gauge(reqTags)
            latency <- histogram(reqTags + status("failure"))
          } yield assertTrue(
            exit == Exit.fail(boom),
            total.count == 1.0,
            active.value == 0.0,
            latency.count == 1L
          )
        } +
        test("uses the provided url namer for the request_url label") {
          val client = new ZioMetricsSttpClient(
            HttpClientZioBackend.stub.whenAnyRequest.thenRespondOk(),
            urlNamer = r => s"custom:${r.uri.path.mkString("/")}"
          )
          val reqTags = Set(
            MetricLabel("request_method", "PATCH"),
            MetricLabel("request_url", "custom:users/123")
          )

          for {
            _       <- basicRequest.patch(uri"http://named.example.com/users/123").send(client)
            total   <- counter(reqTags)
            latency <- histogram(reqTags + status("2xx"))
          } yield assertTrue(
            total.count == 1.0,
            latency.count == 1L
          )
        } +
        test("falls back to N/A when the request has no host") {
          val client  = new ZioMetricsSttpClient(HttpClientZioBackend.stub.whenAnyRequest.thenRespondOk())
          val reqTags = requestLabels("HEAD", "N/A")

          for {
            _     <- basicRequest.head(uri"/relative").send(client)
            total <- counter(reqTags)
          } yield assertTrue(total.count == 1.0)
        }
    }

  private def requestLabels(method: String, url: String): Set[MetricLabel] =
    Set(MetricLabel("request_method", method), MetricLabel("request_url", url))

  private def status(value: String): MetricLabel =
    MetricLabel("response_status", value)

  private def gauge(metricLabels: Set[MetricLabel]): UIO[MetricState.Gauge] =
    Metric.gauge("sttp_active_requests").tagged(metricLabels).value

  private def counter(metricLabels: Set[MetricLabel]): UIO[MetricState.Counter] =
    Metric.counter("sttp_request_total").tagged(metricLabels).value

  private def histogram(metricLabels: Set[MetricLabel]): UIO[MetricState.Histogram] =
    Metric.histogram("sttp_request_latency", boundaries).tagged(metricLabels).value
}
