package io.kaizensolutions.opentelemetry.smithy4s.client

import cats.effect.Resource
import fs2.{text, Stream}
import io.kaizensolutions.opentelemetry.tracerLayer
import io.opentelemetry.api.common.AttributeKey.*
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.http4s.{Response, Status}
import org.http4s.client.Client
import smithy4s.{Transformation, UnsupportedProtocolError}
import smithy4s.http4s.SimpleRestJsonBuilder
import weather.{CityId, WeatherService}
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import zio.test.Assertion.*

import scala.jdk.CollectionConverters.*

object ClientSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Smithy4s 0.19.x client middleware") {
      test("Traces Smithy services") {
        for {
          exporter <- ZIO.service[InMemorySpanExporter]
          client = setupClient(
            ZIO.succeed(
              Response(
                status = Status.Ok,
                body = Stream
                  .emit("""{ "weather": "sunny" }""")
                  .through(text.utf8.encode)
              )
            )
          )
          weather <- setupSmithy(client)
          output  <- weather.getWeather(CityId("London"), "UK")
          spans = exporter.getFinishedSpanItems().asScala.toList
        } yield assert(spans)(hasSize(equalTo(1))) && {
          val span  = spans.head
          val attrs = span.getAttributes()
          assertTrue(
            output.statusCode == 200,
            span.getName == "client.http.request",
            attrs.get(stringKey("client.http.method")) == "GET",
            attrs.get(stringKey("smithy.service.name")) == "WeatherService",
            attrs.get(stringKey("smithy.operation")) == "GetWeather",
            attrs.get(stringKey("resource.name")) == "GET /cities/{cityId}/weather"
          )
        }
      }
    }.provide(tracerLayer)

  val taskToIO: Transformation.SurfaceError[Task, IO] =
    new Transformation.SurfaceError[Task, IO] {
      override def apply[E, A](
          fa: Task[A],
          projectError: Throwable => Option[E]
      ): IO[E, A] = fa.catchAll { t => projectError(t).fold[IO[E, A]](ZIO.die(t))(ZIO.fail(_)) }
    }

  def setupClient(response: Task[Response[Task]]): Client[Task] =
    Client[Task](_ => Resource.eval(response))

  def setupSmithy(
      client: Client[Task]
  ): ZIO[Tracing, UnsupportedProtocolError, WeatherService.ErrorAware[IO]] = {
    ZIO.serviceWithZIO[Tracing] { trace =>
      ZIO
        .fromEither(
          SimpleRestJsonBuilder(WeatherService)
            .client[Task](client)
            .middleware(middleware(trace))
            .make
        )
        .map(_.transform(taskToIO))
    }
  }

}
