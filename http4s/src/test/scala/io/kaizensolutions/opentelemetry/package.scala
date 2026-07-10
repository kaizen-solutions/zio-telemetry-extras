package io.kaizensolutions

import io.opentelemetry.api.trace.Tracer as OtelTracer
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.`export`.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.SdkTracerProvider
import zio.*
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.telemetry.opentelemetry.OpenTelemetry

package object opentelemetry {
  private val inMemoryTracer: UIO[ZEnvironment[InMemorySpanExporter & OtelTracer]] = for {
    spanExporter   <- ZIO.succeed(InMemorySpanExporter.create())
    spanProcessor  <- ZIO.succeed(SimpleSpanProcessor.create(spanExporter))
    tracerProvider <- ZIO.succeed(
      SdkTracerProvider.builder().addSpanProcessor(spanProcessor).build()
    )
    tracer = tracerProvider.get("TracingTest")
  } yield ZEnvironment.empty.add(spanExporter).add(tracer)

  val tracerLayer: ULayer[Tracing & InMemorySpanExporter & OtelTracer] =
    ZLayer.make[Tracing & InMemorySpanExporter & OtelTracer](
      ZLayer.fromZIOEnvironment(inMemoryTracer),
      Tracing.live(logAnnotated = true),
      OpenTelemetry.contextZIO
    )
}
