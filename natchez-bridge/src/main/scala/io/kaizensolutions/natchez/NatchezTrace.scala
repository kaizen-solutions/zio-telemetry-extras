package io.kaizensolutions.natchez

import cats.{~>, Applicative}
import cats.arrow.FunctionK
import cats.effect.Resource
import io.opentelemetry.api.common.*
import io.opentelemetry.api.trace.{Span, SpanContext, SpanKind}
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.Context
import natchez.Span.SpanKind as NSpanKind
import org.typelevel.ci.CIString
import zio.*
import zio.interop.catz.*
import zio.telemetry.opentelemetry.context.{ContextStorage, OutgoingContextCarrier}
import zio.telemetry.opentelemetry.tracing.*
import zio.telemetry.opentelemetry.tracing.propagation.TraceContextPropagator

import java.net.URI
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

import _root_.natchez.{Kernel as NKernel, Span as NSpan, Trace as NTrace, TraceValue}

object NatchezTrace {

  def layer[R: Tag]
      : ZLayer[Tracing & ContextStorage & TraceContextPropagator, Nothing, NTrace[RIO[R, *]]] =
    ZLayer.fromFunction((t: Tracing, ctx: ContextStorage, tp: TraceContextPropagator) =>
      make[R](t, ctx, tp)
    )

  val layer: ZLayer[Tracing & ContextStorage & TraceContextPropagator, Nothing, NTrace[Task]] =
    ZLayer.fromFunction((t: Tracing, ctx: ContextStorage, tp: TraceContextPropagator) =>
      make[Any](t, ctx, tp)
    )

  def make[R](
      t: Tracing,
      ctxStorage: ContextStorage,
      tp: TraceContextPropagator = TraceContextPropagator.default
  ): NTrace[RIO[R, *]] = new NTrace[RIO[R, *]] {
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
          case TraceValue.BooleanValue(value) => b.put(k, value)
          case TraceValue.NumberValue(value)  => putNumber(b, k, value)
          case TraceValue.StringValue(value)  => b.put(k, value)
        }
      }
      b.build()
    }

    private def spanKind(in: NSpanKind): SpanKind = in match {
      case NSpanKind.Internal => SpanKind.INTERNAL
      case NSpanKind.Client   => SpanKind.CLIENT
      case NSpanKind.Server   => SpanKind.SERVER
      case NSpanKind.Producer => SpanKind.PRODUCER
      case NSpanKind.Consumer => SpanKind.CONSUMER
    }

    private def toNatchezKernel(
        in: OutgoingContextCarrier[mutable.Map[String, String]]
    ): NKernel =
      NKernel(in.kernel.map { case (k, v) => CIString(k) -> v }.toMap)

    private val kernelGetter: TextMapGetter[NKernel] = new TextMapGetter[NKernel] {
      override def keys(carrier: NKernel): java.lang.Iterable[String] =
        carrier.toHeaders.keys.map(_.toString).asJava

      override def get(carrier: NKernel, key: String): String =
        carrier.toHeaders.getOrElse(CIString(key), null)
    }

    def kernelToSpanContext(
        context: Context,
        tp: TraceContextPropagator,
        kernel: NKernel
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

    override def kernel: ZIO[R, Throwable, NKernel] = {
      val outgoingCarrier =
        OutgoingContextCarrier.default()
      t.injectSpan(tp, outgoingCarrier)
        .as(toNatchezKernel(outgoingCarrier))
    }

    override def spanR(
        name: String,
        options: NSpan.Options
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

    override def span[A](name: String, options: NSpan.Options)(
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

    override def spanId(implicit F: Applicative[RIO[R, *]]): ZIO[R, Throwable, Option[String]] =
      t.getCurrentSpanContextUnsafe.map(sc => Option.when(sc.isValid())(sc.getSpanId()))

    override def traceUri: ZIO[R, Throwable, Option[URI]] = ZIO.none
  }

}
