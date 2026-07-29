package io.kaizensolutions.opentelemetry

import io.opentelemetry.api.baggage.Baggage
import io.opentelemetry.api.trace.{Span, SpanContext, TraceFlags, TraceState}
import io.opentelemetry.context.Context
import zio.*
import zio.telemetry.opentelemetry.context.{IncomingContextCarrier, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.test.*

import scala.collection.mutable

object PropagatorsSpec extends ZIOSpecDefault {

  private val traceId = "0af7651916cd43dd8448eb211c80319c"
  private val spanId  = "b7ad6b7169203331"

  private val otherTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
  private val otherSpanId  = "00f067aa0ba902b7"

  private def sampledContext(traceId: String, spanId: String): Context = {
    val spanContext =
      SpanContext.create(traceId, spanId, TraceFlags.getSampled, TraceState.getDefault)
    Context.root().`with`(Span.wrap(spanContext))
  }

  private val outgoing: Context = sampledContext(traceId, spanId)

  private def inject(
      propagator: TraceContextPropagator,
      context: Context = outgoing
  ): Map[String, String] = {
    val carrier = OutgoingContextCarrier.default()
    propagator.instance.inject(context, carrier.kernel, carrier)
    carrier.kernel.toMap
  }

  private def extract(propagator: TraceContextPropagator, headers: Map[String, String]): Context = {
    val carrier = IncomingContextCarrier.default(mutable.Map.from(headers))
    propagator.instance.extract(Context.root(), carrier.kernel, carrier)
  }

  private def extractSpanContext(
      propagator: TraceContextPropagator,
      headers: Map[String, String]
  ): SpanContext =
    Span.fromContext(extract(propagator, headers)).getSpanContext

  private def roundTrips(propagator: TraceContextPropagator): TestResult = {
    val extracted = extractSpanContext(propagator, inject(propagator))

    assertTrue(
      extracted.getTraceId == traceId,
      extracted.getSpanId == spanId,
      extracted.isSampled
    )
  }

  override def spec: Spec[TestEnvironment & Scope, Any] =
    suite("Propagators")(
      suite("w3c")(
        test("injects the traceparent header") {
          assertTrue(inject(Propagators.w3c) == Map("traceparent" -> s"00-$traceId-$spanId-01"))
        },
        test("round trips the span context") {
          roundTrips(Propagators.w3c)
        }
      ),
      suite("b3Multi")(
        test("injects the multi header format") {
          assertTrue(
            inject(Propagators.b3Multi) == Map(
              "X-B3-TraceId" -> traceId,
              "X-B3-SpanId"  -> spanId,
              "X-B3-Sampled" -> "1"
            )
          )
        },
        test("round trips the span context") {
          roundTrips(Propagators.b3Multi)
        }
      ),
      suite("b3Single")(
        test("injects the single header format") {
          assertTrue(inject(Propagators.b3Single) == Map("b3" -> s"$traceId-$spanId-1"))
        },
        test("round trips the span context") {
          roundTrips(Propagators.b3Single)
        },
        test("extraction accepts either B3 layout regardless of which one is injected") {
          val single = extractSpanContext(Propagators.b3Multi, inject(Propagators.b3Single))
          val multi  = extractSpanContext(Propagators.b3Single, inject(Propagators.b3Multi))

          assertTrue(
            single.getTraceId == traceId,
            single.getSpanId == spanId,
            multi.getTraceId == traceId,
            multi.getSpanId == spanId
          )
        }
      ),
      suite("w3cBaggage")(
        test("injects trace context and baggage") {
          val context = outgoing.`with`(Baggage.builder().put("tenant", "kaizen").build())

          assertTrue(
            inject(Propagators.w3cBaggage, context) == Map(
              "traceparent" -> s"00-$traceId-$spanId-01",
              "baggage"     -> "tenant=kaizen"
            )
          )
        },
        test("round trips baggage entries") {
          val context   = outgoing.`with`(Baggage.builder().put("tenant", "kaizen").build())
          val extracted = extract(Propagators.w3cBaggage, inject(Propagators.w3cBaggage, context))

          val baggage = Baggage.fromContext(extracted)

          assertTrue(baggage.getEntryValue("tenant") == "kaizen")
        }
      ),
      suite("composite")(
        test("injects the headers of every constituent propagator") {
          val headers = inject(Propagators.composite(Propagators.w3c, Propagators.b3Multi))

          assertTrue(
            headers == Map(
              "traceparent"  -> s"00-$traceId-$spanId-01",
              "X-B3-TraceId" -> traceId,
              "X-B3-SpanId"  -> spanId,
              "X-B3-Sampled" -> "1"
            )
          )
        },
        test("extracts a request carrying only one of the formats") {
          val propagator = Propagators.composite(Propagators.w3c, Propagators.b3Multi)

          val fromW3c = extractSpanContext(propagator, inject(Propagators.w3c))
          val fromB3  = extractSpanContext(propagator, inject(Propagators.b3Multi))

          assertTrue(
            fromW3c.getTraceId == traceId,
            fromW3c.getSpanId == spanId,
            fromB3.getTraceId == traceId,
            fromB3.getSpanId == spanId
          )
        },
        test("resolves conflicting formats in favour of the last propagator that matches") {
          val headers =
            inject(Propagators.w3c) ++ inject(
              Propagators.b3Multi,
              sampledContext(otherTraceId, otherSpanId)
            )

          val b3Wins =
            extractSpanContext(Propagators.composite(Propagators.w3c, Propagators.b3Multi), headers)
          val w3cWins =
            extractSpanContext(Propagators.composite(Propagators.b3Multi, Propagators.w3c), headers)

          assertTrue(
            b3Wins.getTraceId == otherTraceId,
            b3Wins.getSpanId == otherSpanId,
            w3cWins.getTraceId == traceId,
            w3cWins.getSpanId == spanId
          )
        }
      )
    )
}
