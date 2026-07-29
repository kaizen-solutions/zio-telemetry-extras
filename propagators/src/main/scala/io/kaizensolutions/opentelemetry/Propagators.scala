package io.kaizensolutions.opentelemetry

import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.extension.trace.propagation.B3Propagator
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

object Propagators {
  val b3Multi: TraceContextPropagator = instance(B3Propagator.injectingMultiHeaders())

  val b3Single: TraceContextPropagator = instance(B3Propagator.injectingSingleHeader())

  val w3c: TraceContextPropagator = TraceContextPropagator.default

  val w3cBaggage: TraceContextPropagator = composite(
    w3c,
    instance(W3CBaggagePropagator.getInstance())
  )

  /** When using the OpenTelemetry SDK autoconfiguration functionality * */
  def autoConfigured: TraceContextPropagator = instance(
    GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
  )

  def composite(
      head: TraceContextPropagator,
      tail: TraceContextPropagator*
  ): TraceContextPropagator = {
    val propagators = (head +: tail).map(_.instance)
    val composite   = TextMapPropagator.composite(propagators*)
    instance(composite)
  }

  def instance(underlying: TextMapPropagator): TraceContextPropagator =
    new TraceContextPropagator {
      override val instance: TextMapPropagator = underlying
    }
}
