package io.kaizensolutions.opentelemetry.smithy4s.server

import io.kaizensolutions.opentelemetry.{http4s, tracerLayer}
import io.opentelemetry.api.common.AttributeKey.*
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.http4s.{HttpApp, Method, Request}
import org.http4s.syntax.all.*
import org.typelevel.ci.CIStringSyntax
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.Transformation
import weather.ConstantWeatherService
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import zio.test.Assertion.*

import scala.jdk.CollectionConverters.*

object ServerMiddlewareSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override val spec: Spec[TestEnvironment & Scope, Any] = suite(
    "Smithy4s 0.19.x server middleware"
  )(
    test("Traces Smithy services") {
      for {
        exporter <- ZIO.service[InMemorySpanExporter]
        app      <- setupApp
        res <- app.run(Request(method = Method.GET, uri = uri"/cities/123/weather?X-Region=Europe"))
        spans = exporter.getFinishedSpanItems().asScala.toList
      } yield assert(spans)(hasSize(equalTo(1))) && {
        val span  = spans.head
        val attrs = span.getAttributes()

        assertTrue(
          span.getName == "http.request",
          res.headers.get(ci"traceparent").isDefined,
          attrs.get(longKey("http.response.status_code")) == 200,
          attrs.get(stringKey("http.request.method")) == "GET",
          attrs.get(stringKey("http.request.url")) == "/cities/123/weather?X-Region=Europe",
          attrs.get(stringKey("resource.name")) == "GET /cities/{cityId}/weather",
          attrs.get(stringKey("smithy.service.name")) == "WeatherService",
          attrs.get(stringKey("smithy.operation")) == "GetWeather"
        )
      }
    }
  ).provide(tracerLayer)

  def setupApp: RIO[Tracing, HttpApp[Task]] =
    ZIO.serviceWithZIO[Tracing] { trace =>
      ZIO
        .fromEither(
          SimpleRestJsonBuilder
            .routes(ConstantWeatherService.transform(ioToTask))
            .middleware(middleware(trace)) // smithy4s server middleware (enrich traces)
            .make
        )
        .map(_.orNotFound)
        .map(http4s.server.middleware(trace)) // http4s server app middleware (establish traces)
    }

  val ioToTask: Transformation.AbsorbError[IO, Task] = new Transformation.AbsorbError[IO, Task] {

    override def apply[E, A](fa: IO[E, A], injectError: E => Throwable): Task[A] =
      fa.mapError(injectError)

  }
}
