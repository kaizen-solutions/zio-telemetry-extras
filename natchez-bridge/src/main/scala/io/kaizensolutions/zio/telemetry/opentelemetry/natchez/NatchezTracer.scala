package io.kaizensolutions.zio.telemetry.opentelemetry.natchez

import cats.~>
import cats.arrow.FunctionK
import cats.effect.Resource
import io.opentelemetry.api.common.{Attributes, AttributesBuilder}
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.Context
import natchez.{Kernel as NatchezKernel, Span as NatchezSpan, Trace, TraceValue}
import natchez.Span.SpanKind as NatchezSpanKind
import natchez.TraceValue.{BooleanValue, NumberValue, StringValue}
import org.typelevel.ci.CIString
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.context.{ContextStorage, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator
import zio.telemetry.opentelemetry.tracing.Tracing

import java.net.URI
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Based on
  * https://github.com/typelevel/natchez/blob/main/modules/opentelemetry/src/main/scala/natchez/opentelemetry/OpenTelemetrySpan.scala
  */
object NatchezTracer {
  def make[R](
      t: Tracing,
      ctxStorage: ContextStorage,
      tp: TraceContextPropagator = TraceContextPropagator.default
  ): Trace[RIO[R, *]] = new Trace[RIO[R, *]] {
    private def putNumber(b: AttributesBuilder, k: String, n: Number): AttributesBuilder =
      n match {
        case _: java.lang.Byte | _: java.lang.Short | _: java.lang.Integer | _: java.lang.Long |
            _: java.math.BigInteger =>
          b.put(k, n.longValue)
        case _ => // Float, Double, BigDecimal, and anything else
          b.put(k, n.doubleValue)
      }

    private def attributes(fields: Seq[(String, TraceValue)]): Attributes = {
      val b = Attributes.builder()
      fields.foreach { case (k, v) =>
        v match {
          case BooleanValue(value) => b.put(k, value)
          case NumberValue(value)  => putNumber(b, k, value)
          case StringValue(value)  => b.put(k, value)
        }
      }
      b.build()
    }

    private def spanKind(in: NatchezSpanKind): SpanKind = in match {
      case NatchezSpanKind.Internal => SpanKind.INTERNAL
      case NatchezSpanKind.Client   => SpanKind.CLIENT
      case NatchezSpanKind.Server   => SpanKind.SERVER
      case NatchezSpanKind.Producer => SpanKind.PRODUCER
      case NatchezSpanKind.Consumer => SpanKind.CONSUMER
    }

    private def toNatchezKernel(
        in: OutgoingContextCarrier[mutable.Map[String, String]]
    ): NatchezKernel =
      NatchezKernel(in.kernel.map { case (k, v) => CIString(k) -> v }.toMap)

    private val kernelGetter: TextMapGetter[NatchezKernel] = new TextMapGetter[NatchezKernel] {
      override def keys(carrier: NatchezKernel): java.lang.Iterable[String] =
        carrier.toHeaders.keys.map(_.toString).asJava

      override def get(carrier: NatchezKernel, key: String): String =
        carrier.toHeaders.getOrElse(CIString(key), null)
    }

    def kernelToSpanContext(
        context: Context,
        tp: TraceContextPropagator,
        kernel: NatchezKernel
    ): SpanContext = {
      val derivedContext = tp.instance.extract(context, kernel, kernelGetter)
      val extracted      = Span.fromContext(derivedContext).getSpanContext
      SpanContext.createFromRemoteParent(
        extracted.getTraceId,
        extracted.getSpanId,
        extracted.getTraceFlags,
        extracted.getTraceState
      )
    }

    override def put(fields: (String, TraceValue)*): ZIO[R, Throwable, Unit] = {
      t.getCurrentSpanUnsafe.map(_.setAllAttributes(attributes(fields))).unit
    }

    override def log(fields: (String, TraceValue)*): ZIO[R, Throwable, Unit] =
      t.addEventWithAttributes("event", attributes(fields))

    override def log(event: String): ZIO[R, Throwable, Unit] =
      t.addEvent(event)

    override def attachError(
        err: Throwable,
        fields: (String, TraceValue)*
    ): ZIO[R, Throwable, Unit] =
      t.getCurrentSpanUnsafe
        .map(_.recordException(err, attributes(fields)))
        .unit

    override def kernel: ZIO[R, Throwable, NatchezKernel] = {
      val outgoingCarrier =
        OutgoingContextCarrier.default()
      t.injectSpan(tp, outgoingCarrier)
        .as(toNatchezKernel(outgoingCarrier))
    }

    override def spanR(
        name: String,
        options: NatchezSpan.Options
    ): Resource[RIO[R, *], RIO[R, *] ~> RIO[R, *]] = {
      Resource.scopedZIO(
        ctxStorage.get.flatMap { ctx =>
          t.spanScoped(
            spanName = name,
            spanKind = spanKind(options.spanKind),
            links = options.links.map(kernelToSpanContext(ctx, tp, _)).toList
          ).as(new FunctionK[RIO[R, *], RIO[R, *]] {
            override def apply[A](fa: ZIO[R, Throwable, A]): ZIO[R, Throwable, A] = fa
          })
        }
      )
    }

    override def span[A](name: String, options: NatchezSpan.Options)(
        k: ZIO[R, Throwable, A]
    ): ZIO[R, Throwable, A] =
      ctxStorage.get.flatMap { ctx =>
        t.span(
          spanName = name,
          spanKind = spanKind(options.spanKind),
          links = options.links.map(kernelToSpanContext(ctx, tp, _)).toList
        )(k)
      }

    override def traceId: ZIO[R, Throwable, Option[String]] =
      t.getCurrentSpanContextUnsafe.map(sc => Option.when(sc.isValid)(sc.getTraceId))

    override def traceUri: ZIO[R, Throwable, Option[URI]] = ZIO.none

  }
}
