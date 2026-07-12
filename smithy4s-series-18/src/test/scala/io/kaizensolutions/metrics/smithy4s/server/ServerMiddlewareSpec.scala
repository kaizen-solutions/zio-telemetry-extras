package io.kaizensolutions.metrics.smithy4s.server

import io.kaizensolutions.metrics.http4s.ZIOMetricOpsConfig
import org.http4s.{HttpApp, Method, Request, Status}
import org.http4s.syntax.all.*
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.Transformation
import weather.{ConstantWeatherService, WeatherService, WeatherServiceOperation}
import zio.*
import zio.interop.catz.*
import zio.metrics.{Metric, MetricLabel, MetricState}
import zio.test.*

object ServerMiddlewareSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override val spec: Spec[TestEnvironment & Scope, Any] = suite(
    "Smithy4s 0.18.x metrics server middleware"
  )(
    test("records successful requests with Smithy labels") {
      val prefix     = "smithy4s_18_server_success"
      val classifier = "weather"
      val config     = ZIOMetricOpsConfig.default
        .withMetricPrefix(prefix)
        .addLabels(Set(MetricLabel("test", "success")))

      val baseLabels = endpointLabels(
        config,
        operation = "GetWeather",
        resource = "GET /cities/{cityId}/weather"
      )
      val classifiedLabels = baseLabels + MetricLabel("classifier", classifier)

      for {
        app      <- setupApp(config, _ => Some(classifier))
        response <- app.run(
          Request(method = Method.GET, uri = uri"/cities/123/weather?X-Region=Europe")
        )
        _        <- response.body.compile.drain
        active   <- gauge(s"${prefix}_active_requests", classifiedLabels)
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
        response.status == Status.Ok,
        active.value == 0.0,
        requests.count == 1.0,
        headers.count == 1L,
        body.count == 1L
      )
    },
    test("records failures with endpoint-specific Smithy labels") {
      val prefix     = "smithy4s_18_server_failure"
      val classifier = "cities"
      val failure    = new RuntimeException("Boom!")
      val config     = ZIOMetricOpsConfig.default
        .withMetricPrefix(prefix)
        .addLabels(Set(MetricLabel("test", "failure")))
      val baseLabels = endpointLabels(
        config,
        operation = "CreateCity",
        resource = "POST /cities"
      )
      val classifiedLabels  = baseLabels + MetricLabel("classifier", classifier)
      val terminationLabels = classifiedLabels ++ Set(
        MetricLabel("termination_type", "error"),
        MetricLabel("cause", classOf[RuntimeException].getName)
      )
      val failingApp = HttpApp[Task](_ => ZIO.fail(failure))
      val app        = middleware[Any](config, _ => Some(classifier))
        .prepare(WeatherService)(WeatherServiceOperation.CreateCity)(failingApp)

      for {
        result   <- app.run(Request(method = Method.POST, uri = uri"/cities")).either
        abnormal <- counter(s"${prefix}_abnormal_count", terminationLabels)
        duration <- histogram(s"${prefix}_abnormal_latency", config, terminationLabels)
      } yield assertTrue(
        result == Left(failure),
        abnormal.count == 1.0,
        duration.count == 1L
      )
    }
  )

  private def setupApp(
      config: ZIOMetricOpsConfig,
      requestClassifier: Request[Task] => Option[String]
  ): Task[HttpApp[Task]] =
    ZIO
      .fromEither(
        SimpleRestJsonBuilder
          .routes(ConstantWeatherService.transform(ioToTask))
          .middleware(middleware[Any](config, requestClassifier))
          .make
      )
      .map(_.orNotFound)

  private val ioToTask: Transformation.AbsorbError[IO, Task] =
    new Transformation.AbsorbError[IO, Task] {
      override def apply[E, A](fa: IO[E, A], injectError: E => Throwable): Task[A] =
        fa.mapError(injectError)
    }

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
