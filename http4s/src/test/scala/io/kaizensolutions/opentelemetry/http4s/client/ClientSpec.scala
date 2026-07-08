package io.kaizensolutions.opentelemetry.http4s.client

import cats.effect.Resource
import io.kaizensolutions.opentelemetry.opentelemetry.tracerLayer
import io.opentelemetry.api.common.AttributeKey.*
import io.opentelemetry.api.trace.{SpanKind, StatusCode}
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.http4s.*
import org.http4s.client.Client
import org.http4s.syntax.all.*
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
    suite("http4s client middleware") {
      test("traces successful requests") {
        for {
          client <- setupClient(ZIO.succeed(Response[Task](Status.Ok)))
          req = Request[Task](Method.GET, uri"https://example.com/hello/42")
          _              <- ZIO.scoped(client.run(req).toScopedZIO)
          exporter       <- ZIO.service[InMemorySpanExporter]
          completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
        } yield assert(completedSpans)(hasSize(equalTo(1))) && {
          val span = completedSpans.head
          val attr = span.getAttributes()

          assertTrue(span.getName() == "client.http.request") &&
          assertTrue(span.getKind() == SpanKind.CLIENT) &&
          assertTrue(attr.get(stringKey("client.http.url")) == "https://example.com/hello/42") &&
          assertTrue(attr.get(stringKey("client.http.method")) == "GET") &&
          assertTrue(attr.get(stringKey("client.http.version")) == "HTTP/1.1") &&
          assertTrue(attr.get(longKey("client.http.status_code")) == 200)
        }
      } +
        test("records non-success status codes") {
          for {
            client <- setupClient(ZIO.succeed(Response[Task](Status.ServiceUnavailable)))
            req = Request[Task](Method.GET, uri"https://boom.com/unavailable")
            _              <- client.run(req).use_
            exporter       <- ZIO.service[InMemorySpanExporter]
            completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
          } yield assert(completedSpans)(hasSize(equalTo(1))) && {
            val span = completedSpans.head
            val attr = span.getAttributes()

            assertTrue(span.getName() == "client.http.request") &&
            assertTrue(span.getKind() == SpanKind.CLIENT) &&
            assertTrue(attr.get(stringKey("client.http.url")) == "https://boom.com/unavailable") &&
            assertTrue(attr.get(stringKey("client.http.method")) == "GET") &&
            assertTrue(attr.get(stringKey("client.http.version")) == "HTTP/1.1") &&
            assertTrue(attr.get(longKey("client.http.status_code")) == 503)
          }
        } +
        test("propagates client failures and finishes span as error") {
          for {
            client <- setupClient(ZIO.fail(new RuntimeException("Boom!")))
            req = Request[Task](Method.POST, uri"https://example.com/fail")
            res            <- ZIO.scoped(client.run(req).toScopedZIO).either
            exporter       <- ZIO.service[InMemorySpanExporter]
            completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
          } yield assertTrue(res.fold(_.getMessage == "Boom!", _ => false)) &&
            assert(completedSpans)(hasSize(equalTo(1))) && {
              val span = completedSpans.head
              val attr = span.getAttributes()

              assertTrue(span.getName() == "client.http.request") &&
              assertTrue(span.getKind() == SpanKind.CLIENT) &&
              assertTrue(span.getStatus().getStatusCode() == StatusCode.ERROR) &&
              assertTrue(attr.get(stringKey("client.http.url")) == "https://example.com/fail") &&
              assertTrue(attr.get(stringKey("client.http.method")) == "POST") &&
              assertTrue(attr.get(stringKey("client.http.version")) == "HTTP/1.1") &&
              assertTrue(Option(attr.get(longKey("client.http.status_code"))).isEmpty)
            }
        } +
        test("propagates client defects and finishes span as error") {
          for {
            client <- setupClient(ZIO.die(new IllegalArgumentException("Bang!")))
            req = Request[Task](Method.DELETE, uri"https://example.com/boom")
            resp           <- ZIO.scoped(client.run(req).toScopedZIO).exit
            exporter       <- ZIO.service[InMemorySpanExporter]
            completedSpans <- ZIO.succeed(exporter.getFinishedSpanItems().asScala.toList)
          } yield assertTrue(resp.is(_.die).getMessage == "Bang!") &&
            assert(completedSpans)(hasSize(equalTo(1))) && {
              val span = completedSpans.head
              val attr = span.getAttributes()

              assertTrue(span.getName() == "client.http.request") &&
              assertTrue(span.getKind() == SpanKind.CLIENT) &&
              assertTrue(span.getStatus().getStatusCode() == StatusCode.ERROR) &&
              assertTrue(attr.get(stringKey("client.http.url")) == "https://example.com/boom") &&
              assertTrue(attr.get(stringKey("client.http.method")) == "DELETE") &&
              assertTrue(attr.get(stringKey("client.http.version")) == "HTTP/1.1") &&
              assertTrue(Option(attr.get(longKey("client.http.status_code"))).isEmpty)
            }
        }
    }.provide(tracerLayer)

  def setupClient(response: Task[Response[Task]]) = ZIO.serviceWith[Tracing] { t =>
    val underlying = Client[Task](_ => Resource.eval(response))
    middleware(t)(underlying)
  }
}
