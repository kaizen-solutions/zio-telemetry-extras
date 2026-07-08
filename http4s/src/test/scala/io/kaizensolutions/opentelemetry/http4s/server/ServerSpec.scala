package io.kaizensolutions.opentelemetry.http4s.server

import io.kaizensolutions.opentelemetry.opentelemetry.tracerLayer
import io.opentelemetry.api.common.AttributeKey.*
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.syntax.all.*
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.test.*
import zio.test.Assertion.*

import scala.jdk.CollectionConverters.*

object ServerSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("http4s server middleware") {
      test("traces successful requests") {
        for {
          app <- setupApp
          req = Request[Task](Method.GET, uri"/hello/42")
          _              <- app.run(req)
          exporter       <- ZIO.service[InMemorySpanExporter]
          completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
        } yield assert(completedSpans)(hasSize(equalTo(1))) && {
          val span       = completedSpans.head
          val attributes = span.getAttributes()

          assertTrue(span.getName() == "http.request") &&
          assertTrue(attributes.get(stringKey("http.request.url")) == "/hello/42") &&
          assertTrue(attributes.get(stringKey("http.request.method")) == "GET") &&
          assertTrue(attributes.get(longKey("http.response.status_code")) == 200)
        }
      } +
        test("traces errors") {
          for {
            app <- setupApp
            req = Request[Task](Method.POST, uri"/fail")
            res            <- app.run(req).either
            exporter       <- ZIO.service[InMemorySpanExporter]
            completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
          } yield assertTrue(res.isLeft) &&
            assert(completedSpans)(hasSize(equalTo(1))) && {
              val span       = completedSpans.head
              val attributes = span.getAttributes()
              val events     = span.getEvents().asScala.toList

              assertTrue(span.getName() == "http.request") &&
              assertTrue(attributes.get(stringKey("http.request.url")) == "/fail") &&
              assertTrue(attributes.get(stringKey("http.request.method")) == "POST") &&
              assert(events)(hasSize(equalTo(1))) && {
                val event      = events.head
                val attributes = event.getAttributes()

                assertTrue(event.getName() == "exception") &&
                assertTrue(attributes.get(stringKey("exception.message")) == "Boom!")
              }
            }
        } +
        test("traces defects") {
          for {
            app <- setupApp
            req = Request[Task](Method.DELETE, uri"/boom")
            resp           <- app.run(req).exit
            exporter       <- ZIO.service[InMemorySpanExporter]
            completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
          } yield assertTrue(resp.is(_.die).getMessage == "Bang!") &&
            assert(completedSpans)(hasSize(equalTo(1))) && {
              val span       = completedSpans.head
              val attributes = span.getAttributes()
              println(span)

              assertTrue(span.getName() == "http.request") &&
              assertTrue(attributes.get(stringKey("http.request.url")) == "/boom") &&
              assertTrue(attributes.get(stringKey("http.request.method")) == "DELETE") &&
              assertTrue(attributes.get(stringKey("cause")).contains("IllegalArgumentException"))
            }
        }
    }.provide(tracerLayer)

  object dsl extends Http4sDsl[Task]
  import dsl.*

  val setupApp = ZIO.serviceWith[Tracing] { t =>
    val routes: HttpRoutes[Task] =
      HttpRoutes
        .of[Task] {
          case GET -> Root / "hello" / id =>
            Ok(s"Hello, $id!")

          case POST -> Root / "fail" =>
            ZIO.fail(new RuntimeException("Boom!"))

          case DELETE -> Root / "boom" =>
            ZIO.die(new IllegalArgumentException("Bang!")) *> NotImplemented()
        }

    middleware(t)(routes.orNotFound)
  }
}
