package io.kaizensolutions.metrics.smithy4s.client

import cats.effect.Resource
import fs2.{text, Stream}
import io.kaizensolutions.metrics.http4s.ZIOMetricOpsConfig
import org.http4s.{Request, Response, Status}
import org.http4s.client.Client
import smithy4s.UnsupportedProtocolError
import smithy4s.http4s.SimpleRestJsonBuilder
import weather.{CityId, WeatherService}
import zio.*
import zio.interop.catz.*
import zio.metrics.{Metric, MetricLabel, MetricState}
import zio.test.*

object ClientMiddlewareSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override val spec: Spec[TestEnvironment & Scope, Any] = suite(
    "Smithy4s 0.18.x metrics client middleware"
  )(
    test("records successful requests with Smithy labels") {
      val prefix     = "smithy4s_18_client_success"
      val classifier = "weather"
      val config = ZIOMetricOpsConfig.default
        .withMetricPrefix(prefix)
        .addLabels(Set(MetricLabel("test", "success")))
      val baseLabels = endpointLabels(
        config,
        operation = "GetWeather",
        resource = "GET /cities/{cityId}/weather"
      )
      val classifiedLabels = baseLabels + MetricLabel("classifier", classifier)
      val response: Task[Response[Task]] = ZIO.succeed(
        Response(
          status = Status.Ok,
          body = Stream
            .emit("""{ "weather": "sunny" }""")
            .through(text.utf8.encode)
        )
      )

      for {
        weather <- setupSmithy(setupClient(response), config, _ => Some(classifier))
        output  <- weather.getWeather(CityId("London"), "UK")
        active  <- gauge(s"${prefix}_active_requests", classifiedLabels)
        requests <- counter(
          s"${prefix}_request_count",
          classifiedLabels ++ Set(
            MetricLabel("method", "GET"),
            MetricLabel("status", Status.Ok.code.toString)
          )
        )
        headers <- histogram(
          s"${prefix}_response_latency",
          config,
          classifiedLabels ++ Set(
            MetricLabel("method", "GET"),
            MetricLabel("phase", "headers")
          )
        )
        body <- histogram(
          s"${prefix}_response_latency",
          config,
          classifiedLabels ++ Set(
            MetricLabel("method", "GET"),
            MetricLabel("status_bucket", "2xx"),
            MetricLabel("phase", "body")
          )
        )
      } yield assertTrue(
        output.statusCode == Status.Ok.code,
        active.value == 0.0,
        requests.count == 1.0,
        headers.count == 1L,
        body.count == 1L
      )
    },
    test("records failures with endpoint-specific Smithy labels") {
      val prefix     = "smithy4s_18_client_failure"
      val classifier = "cities"
      val failure    = new RuntimeException("Boom!")
      val config = ZIOMetricOpsConfig.default
        .withMetricPrefix(prefix)
        .addLabels(Set(MetricLabel("test", "failure")))
      val baseLabels = endpointLabels(
        config,
        operation = "CreateCity",
        resource = "POST /cities"
      )
      val classifiedLabels = baseLabels + MetricLabel("classifier", classifier)
      val terminationLabels = classifiedLabels ++ Set(
        MetricLabel("termination_type", "error"),
        MetricLabel("cause", classOf[RuntimeException].getName)
      )
      val failedResponse: Task[Response[Task]] = ZIO.fail(failure)

      for {
        weather  <- setupSmithy(setupClient(failedResponse), config, _ => Some(classifier))
        result   <- weather.createCity("Toronto", "Canada").either
        abnormal <- counter(s"${prefix}_abnormal_count", terminationLabels)
        duration <- histogram(s"${prefix}_abnormal_latency", config, terminationLabels)
      } yield assertTrue(
        result == Left(failure),
        abnormal.count == 1.0,
        duration.count == 1L
      )
    }
  )

  private def setupClient(response: Task[Response[Task]]): Client[Task] =
    Client[Task](_ => Resource.eval(response))

  private def setupSmithy(
      client: Client[Task],
      config: ZIOMetricOpsConfig,
      requestClassifier: Request[Task] => Option[String]
  ): IO[UnsupportedProtocolError, WeatherService[Task]] =
    ZIO
      .fromEither(
        SimpleRestJsonBuilder(WeatherService)
          .client[Task](client)
          .middleware(middleware[Any](config, requestClassifier))
          .make
      )

  private def endpointLabels(
      config: ZIOMetricOpsConfig,
      operation: String,
      resource: String
  ): Set[MetricLabel] =
    config.metricLabels ++ Set(
      MetricLabel("smithy.service.name", "WeatherService"),
      MetricLabel("smithy.operation", operation),
      MetricLabel("resource.name", resource)
    )

  private def gauge(name: String, labels: Set[MetricLabel]): UIO[MetricState.Gauge] =
    Metric.gauge(name).tagged(labels).value

  private def counter(name: String, labels: Set[MetricLabel]): UIO[MetricState.Counter] =
    Metric.counter(name).tagged(labels).value

  private def histogram(
      name: String,
      config: ZIOMetricOpsConfig,
      labels: Set[MetricLabel]
  ): UIO[MetricState.Histogram] =
    Metric.histogram(name, config.histogramBoundaries).tagged(labels).value
}
