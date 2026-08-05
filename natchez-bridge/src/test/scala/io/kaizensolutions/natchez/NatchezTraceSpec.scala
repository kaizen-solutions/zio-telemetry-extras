package io.kaizensolutions.natchez

import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.trace.Tracer as JTracer
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.SdkTracerProvider
import zio.*
import zio.telemetry.opentelemetry.context.{ContextStorage, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.OpenTelemetry as ZioTelemetry
import zio.test.*

import scala.jdk.CollectionConverters.*

import _root_.natchez.Trace as NatchezTracing

/** Exercises the interplay between the `natchez.Trace` produced by [[NatchezTrace]] and the
  * `zio.telemetry.opentelemetry.tracing.Tracing` of zio-telemetry when both are backed by the same
  * OpenTelemetry SDK.
  *
  * Both APIs are expected to observe a single, shared notion of "the current span", so spans
  * created by either one nest under whichever span is currently in scope, regardless of which API
  * opened it.
  */
object NatchezTraceSpec extends ZIOSpecDefault {

  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    Runtime.removeDefaultLoggers ++ testEnvironment

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("NatchezTrace interop with zio-telemetry Tracing")(
      interop("JVM thread-local context storage")
    )

  private def interop(
      storageName: String
  ): Spec[Any, Throwable] =
    suite(storageName)(
      test("a natchez span nests underneath a zio-telemetry span") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          _       <- tracing.span("zio-outer")(natchez.span("natchez-inner")(ZIO.unit))
          parents <- parentsByName
        } yield assertTrue(parents == Map("zio-outer" -> Root, "natchez-inner" -> "zio-outer"))
      },
      test("a zio-telemetry span nests underneath a natchez span") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          _       <- natchez.span("natchez-outer") {
            tracing.span("zio-inner") {
              ZIO.unit
            }
          }
          parents <- parentsByName
        } yield assertTrue(parents == Map("natchez-outer" -> Root, "zio-inner" -> "natchez-outer"))
      },
      test("alternating natchez and zio-telemetry spans form a single trace") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          _       <- tracing.span("zio-1") {
            natchez.span("natchez-2") {
              tracing.span("zio-3") {
                natchez.span("natchez-4")(ZIO.unit)
              }
            }
          }
          spans   <- finishedSpans
          parents <- parentsByName
        } yield assertTrue(
          parents == Map(
            "zio-1"     -> Root,
            "natchez-2" -> "zio-1",
            "zio-3"     -> "natchez-2",
            "natchez-4" -> "zio-3"
          ),
          spans.map(_.getTraceId).distinct.size == 1
        )
      },
      test("sibling spans opened by either API share the same parent") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          _       <- natchez.span("parent") {
            natchez.span("natchez-sibling")(ZIO.unit) *>
              tracing.span("zio-sibling")(ZIO.unit)
          }
          parents <- parentsByName
        } yield assertTrue(
          parents == Map(
            "parent"          -> Root,
            "natchez-sibling" -> "parent",
            "zio-sibling"     -> "parent"
          )
        )
      },
      test("leaving a span restores the enclosing span for both APIs") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          _       <- tracing.span("root") {
            natchez.span("first-child")(ZIO.unit) *>
              natchez.span("second-child")(ZIO.unit) *>
              tracing.span("third-child")(ZIO.unit)
          }
          parents <- parentsByName
        } yield assertTrue(
          parents == Map(
            "root"         -> Root,
            "first-child"  -> "root",
            "second-child" -> "root",
            "third-child"  -> "root"
          )
        )
      },
      test("attributes written through either API land on the innermost span") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          _       <- tracing.span("outer") {
            tracing.setAttribute("written.by", "zio-telemetry") *>
              natchez.span("inner")(natchez.put("written.by" -> "natchez"))
          }
          spans <- finishedSpans
          byName = spans.map(span => span.getName -> span).toMap
        } yield assertTrue(
          byName("outer").getAttributes.get(stringKey("written.by")) == "zio-telemetry",
          byName("inner").getAttributes.get(stringKey("written.by")) == "natchez"
        )
      },
      test("both APIs propagate the same span context to a remote system") {
        for {
          tracing <- ZIO.service[Tracing]
          natchez <- ZIO.service[NatchezTracing[Task]]
          kernels <- natchez.span("remote-caller") {
            for {
              natchezKernel <- natchez.kernel
              carrier = OutgoingContextCarrier.default()
              _ <- tracing.injectSpan(TraceContextPropagator.default, carrier)
            } yield (
              natchezKernel.toHeaders.map { case (k, v) => k.toString.toLowerCase -> v },
              carrier.kernel.toMap
            )
          }
          (natchezHeaders, zioHeaders) = kernels
          spans <- finishedSpans
          caller = spans.find(_.getName == "remote-caller").get
        } yield assertTrue(
          natchezHeaders == zioHeaders,
          natchezHeaders.get("traceparent").exists(_.contains(caller.getSpanId))
        )
      }
    ).provide(tracerLayer)

  private val Root = "<root>"

  private def finishedSpans: URIO[InMemorySpanExporter, List[SpanData]] =
    ZIO.serviceWith[InMemorySpanExporter](_.getFinishedSpanItems.asScala.toList)

  /** Maps every finished span's name to the name of its parent, or [[Root]] when it has none. */
  private def parentsByName: URIO[InMemorySpanExporter, Map[String, String]] =
    finishedSpans.map { spans =>
      val namesBySpanId = spans.map(span => span.getSpanId -> span.getName).toMap
      spans.map(span => span.getName -> namesBySpanId.getOrElse(span.getParentSpanId, Root)).toMap
    }

  private val inMemoryTracer: UIO[ZEnvironment[InMemorySpanExporter & JTracer]] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(
      SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build()
    )
    tracer = tracerProvider.get("TracingTest")
  } yield ZEnvironment.empty.add(spanExporter).add(tracer)

  val tracerLayer: ULayer[Tracing & InMemorySpanExporter & NatchezTracing[Task]] =
    ZLayer.make[Tracing & InMemorySpanExporter & NatchezTracing[Task]](
      ZLayer.fromZIOEnvironment(inMemoryTracer),
      Tracing.live(logAnnotated = true),
      ZioTelemetry.contextZIO,
      ZLayer.fromFunction((tracing: Tracing, contextStorage: ContextStorage) =>
        NatchezTrace.make[Any](tracing, contextStorage)
      )
    )
}
