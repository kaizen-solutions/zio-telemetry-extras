package io.kaizensolutions.opentelemetry.http4s

import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

final case class TracingConfig(propagator: TraceContextPropagator)
object TracingConfig {
  val default: TracingConfig = TracingConfig(TraceContextPropagator.default)
}
